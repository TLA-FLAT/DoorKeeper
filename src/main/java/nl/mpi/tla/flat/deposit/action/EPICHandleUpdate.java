/*
 * Copyright (C) 2015-2017 The Language Archive
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package nl.mpi.tla.flat.deposit.action;

import org.fcrepo.client.FcrepoResponse;
import java.io.File;
import java.net.URI;
import nl.knaw.meertens.pid.PIDService;
import nl.mpi.tla.flat.deposit.Context;
import nl.mpi.tla.flat.deposit.DepositException;
import nl.mpi.tla.flat.deposit.sip.Collection;
import nl.mpi.tla.flat.deposit.sip.Resource;
import nl.mpi.tla.flat.deposit.sip.SIPInterface;
import org.apache.commons.configuration.XMLConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Repoints the EPIC handles minted by {@link EPICHandleCreation} from their
 * temporary "current version" target to the version-specific Fedora 6 memento
 * URL.
 *
 * Fedora 6 mementos can only be created on a committed resource (the memento
 * builders can't join an open transaction), so this action must run <em>after</em>
 * the Fedora transaction has been committed. For each object written by this
 * deposit it explicitly creates a memento of the datastream and updates the
 * handle to point at it.
 *
 * @author menzowi
 * @author pavsri
 */
public class EPICHandleUpdate extends FedoraAction {

    private static final Logger logger = LoggerFactory.getLogger(EPICHandleUpdate.class.getName());

    @Override
    public boolean perform(Context context) throws DepositException {
        try {
            connect(context);

            String localServer  = fedoraConfig.getString("localServer");
            String publicServer = fedoraConfig.getString("publicServer");

            String epic = this.getParameter("epicConfig");
            if (epic == null) {
                logger.error("No EPIC configuration has been specified! Use the epicConfig parameter.");
                return false;
            }
            File config = new File(epic);
            if (!config.exists()) {
                logger.error("The EPIC configuration["+epic+"] doesn't exist!");
                return false;
            } else if (!config.isFile()) {
                logger.error("The EPIC configuration["+epic+"] isn't a file!");
                return false;
            } else if (!config.canRead()) {
                logger.error("The EPIC configuration["+epic+"] can't be read!");
                return false;
            }
            logger.debug("EPIC configuration["+config.getAbsolutePath()+"]");

            XMLConfiguration xConfig = new XMLConfiguration(config);
            boolean isTest = xConfig.getString("status") != null && xConfig.getString("status").equals("test");
            PIDService ps = PIDService.create(xConfig, null);

            SIPInterface sip = context.getSIP();

            // the deposited compound object
            if (sip.hasPID() && sip.hasFID())
                relocate(ps, isTest, localServer, publicServer, sip.getFID(), sip.getPID());
            else
                logger.debug("SIP has no PID and/or FID; nothing to update");

            // collections referenced/updated by this deposit
            for (Collection col : sip.getCollections(true)) {
                if (col.hasPID() && col.hasFID())
                    relocate(ps, isTest, localServer, publicServer, col.getFID(), col.getPID());
                else
                    logger.debug("Collection["+col+"] has no PID and/or FID; skipped");
            }

            // resources written by this deposit
            for (Resource res : sip.getResources()) {
                if (res.isInsert() || res.isUpdate()) {
                    if (res.hasPID() && res.hasFID())
                        relocate(ps, isTest, localServer, publicServer, res.getFID(), res.getPID());
                    else
                        logger.debug("Resource["+res+"] has no PID and/or FID; skipped");
                }
            }
        } catch (DepositException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new DepositException(ex);
        }
        return true;
    }

    /**
     * Create a memento of the object's datastream and repoint its handle to it.
     */
    protected void relocate(PIDService ps, boolean isTest, String localServer, String publicServer, URI fidUri, URI pidUri) throws DepositException {
        try {
            String fid  = fidUri.toString().replaceAll("#.*","");
            String frag = fidUri.getRawFragment();
            if (frag == null) {
                logger.warn("FID["+fidUri+"] isn't complete; skipping memento/handle update");
                return;
            }
            String dsid = frag.replaceAll("@.*","");

            // 1. create an explicit memento of the (committed) datastream
            URI dsUri = new URI(localServer+"/"+fid+"/"+dsid);
            String memento = createMemento(dsUri);
            // the memento lives on the internal REST endpoint; expose it via the public server
            String loc = memento.replace(localServer, publicServer);

            // 2. repoint the handle from its temporary target to the memento URL
            String pid    = pidUri.toString().replaceAll("^http(s?)://hdl.handle.net/","hdl:");
            String prefix = pid.replaceAll("hdl:([^/]*)/.*","$1");
            String uuid   = pid.replaceAll(".*/","");

            logger.info("Update handle["+prefix+"/"+uuid+"] -> memento URI["+loc+"]");
            if (!isTest)
                ps.updateLocation(prefix+"/"+uuid, loc);
            logger.info("Updated handle["+prefix+"/"+uuid+"] -> URI["+loc+"]");
        } catch (DepositException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new DepositException(ex);
        }
    }

    /**
     * Explicitly create a Fedora 6 memento (version) of {@code uri} and return
     * the memento's URI as reported by the {@code Location} response header.
     */
    protected String createMemento(URI uri) throws DepositException {
        // FcrepoClient.createMemento expects the TimeMap (LDPCv) URI, not the resource itself
        URI timemap = URI.create(uri.toString()+"/fcr:versions");
        try (FcrepoResponse response = fedoraClient.createMemento(timemap).perform()) {
            logger.debug("createMemento["+uri+"] code["+response.getStatusCode()+"]");
            if (response.getStatusCode() >= 300)
                throw new DepositException("Couldn't create a memento for ["+uri+"], status["+response.getStatusCode()+"]");
            URI loc = response.getLocation();
            if (loc == null)
                throw new DepositException("Fedora didn't return a memento Location for ["+uri+"]!");
            logger.debug("created memento["+loc+"]");
            return loc.toString();
        } catch (DepositException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new DepositException("Creating a memento for ["+uri+"] failed!", ex);
        }
    }
}
