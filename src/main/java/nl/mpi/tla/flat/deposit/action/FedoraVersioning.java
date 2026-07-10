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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * Snapshots each object touched by this deposit and repoints the freshly minted
 * EPIC handle to the version-specific memento URL.
 *
 * <p>For every object (SIP, referenced collection, inserted/updated resource)
 * that has a PID and FID this action creates a Fedora 6 memento of the LDP-RS
 * container plus of every managed child datastream that exists on the object
 * (CMD, DC, OLAC, OBJ, TECHMD). This gives every deposit a complete, addressable
 * snapshot -- first version included -- and lets each per-version handle resolve
 * to the datastream memento captured for its deposit.</p>
 *
 * <p>Fedora 6 mementos can only be created on committed resources (the memento
 * builders can't join an open transaction), so this action must run <em>after</em>
 * the Fedora transaction has been committed.</p>
 *
 * @author menzowi
 * @author pavsri
 */
public class FedoraVersioning extends FedoraAction {

    private static final Logger logger = LoggerFactory.getLogger(FedoraVersioning.class.getName());

    /** Datastream identifiers that carry managed content and should be versioned per deposit. */
    private static final List<String> VERSIONED_DATASTREAMS = List.of("CMD", "DC", "OLAC", "OBJ", "TECHMD");

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
                versionAndRelocate(ps, isTest, localServer, publicServer, sip.getFID(), sip.getPID());
            else
                logger.debug("SIP has no PID and/or FID; nothing to version");

            // collections referenced/updated by this deposit
            for (Collection col : sip.getCollections(true)) {
                if (col.hasPID() && col.hasFID())
                    versionAndRelocate(ps, isTest, localServer, publicServer, col.getFID(), col.getPID());
                else
                    logger.debug("Collection["+col+"] has no PID and/or FID; skipped");
            }

            // resources written by this deposit
            for (Resource res : sip.getResources()) {
                if (res.isInsert() || res.isUpdate()) {
                    if (res.hasPID() && res.hasFID())
                        versionAndRelocate(ps, isTest, localServer, publicServer, res.getFID(), res.getPID());
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
     * Snapshot the object's container and every managed datastream, then repoint
     * its handle to the memento of the datastream named in the FID's fragment.
     */
    protected void versionAndRelocate(PIDService ps, boolean isTest, String localServer, String publicServer, URI fidUri, URI pidUri) throws DepositException {
        try {
            String fid  = fidUri.toString().replaceAll("#.*","");
            String frag = fidUri.getRawFragment();
            if (frag == null) {
                logger.warn("FID["+fidUri+"] isn't complete; skipping memento/handle update");
                return;
            }
            String handleDsid = frag.replaceAll("@.*","");

            // 1. memento the container itself (captures RELS-EXT + DC-in-RDF + object properties)
            URI containerUri = new URI(localServer+"/"+fid);
            createMemento(containerUri);

            // 2. memento every managed datastream that exists on the object
            Map<String,String> dsMementos = new LinkedHashMap<>();
            for (String ds : VERSIONED_DATASTREAMS) {
                if (!Boolean.TRUE.equals(fcrepo_exists(new URI(fid), ds, null))) {
                    logger.debug("FID["+fid+"] has no ["+ds+"] datastream; not versioned");
                    continue;
                }
                URI dsUri = new URI(localServer+"/"+fid+"/"+ds);
                dsMementos.put(ds, createMemento(dsUri));
            }

            // 3. repoint the handle from its temporary target to the memento URL of the datastream in the FID fragment
            String memento = dsMementos.get(handleDsid);
            if (memento == null) {
                // the datastream named in the FID fragment doesn't exist on the object; nothing to point the handle at
                throw new DepositException("FID["+fidUri+"] refers to datastream ["+handleDsid+"] but no such datastream exists on the object");
            }
            // the memento lives on the internal REST endpoint; expose it via the public server
            String loc = memento.replace(localServer, publicServer);

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
