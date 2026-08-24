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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
 * EPIC handle to the timestamp-addressed Drupal version page.
 *
 * <p>For every object (SIP, referenced collection, inserted/updated resource)
 * that has a PID and FID this action creates a Fedora 6 memento of the LDP-RS
 * container plus of every managed child datastream that exists on the object
 * (CMD, DC, OLAC, OBJ, TECHMD). This gives every deposit a complete, addressable
 * snapshot -- first version included. Each per-version handle resolves to the
 * public Drupal history route, which combines the matching node revision with
 * the Fedora datastream memento captured for the deposit.</p>
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

            String localServer = fedoraConfig.getString("localServer");
            String publicDrupal = loadPublicDrupalServer();

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
                versionAndRelocate(ps, isTest, localServer, publicDrupal, sip.getFID(), sip.getPID());
            else
                logger.debug("SIP has no PID and/or FID; nothing to version");

            // collections referenced/updated by this deposit
            for (Collection col : sip.getCollections(true)) {
                if (col.hasPID() && col.hasFID())
                    versionAndRelocate(ps, isTest, localServer, publicDrupal, col.getFID(), col.getPID());
                else
                    logger.debug("Collection["+col+"] has no PID and/or FID; skipped");
            }

            // resources written by this deposit
            for (Resource res : sip.getResources()) {
                if (res.isInsert() || res.isUpdate()) {
                    if (res.hasPID() && res.hasFID())
                        versionAndRelocate(ps, isTest, localServer, publicDrupal, res.getFID(), res.getPID());
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
     * Snapshot the object and repoint its handle to Drupal's version page.
     */
    protected void versionAndRelocate(PIDService ps, boolean isTest, String localServer, String publicDrupal, URI fidUri, URI pidUri) throws DepositException {
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

            // 3. Repoint the handle to the public Drupal version route. The
            // timestamp is the exact Fedora memento identifier, allowing the
            // UI to serve the correct archived datastream and pair it with the
            // nearest Drupal node revision.
            String memento = dsMementos.get(handleDsid);
            if (memento == null) {
                // the datastream named in the FID fragment doesn't exist on the object; nothing to point the handle at
                throw new DepositException("FID["+fidUri+"] refers to datastream ["+handleDsid+"] but no such datastream exists on the object");
            }
            String timestamp = memento.replaceAll(".*/", "");
            if (!timestamp.matches("[0-9]{14}")) {
                throw new DepositException("Fedora memento["+memento+"] has no compact UTC timestamp identifier");
            }
            String encodedFid = URLEncoder.encode(fid, StandardCharsets.UTF_8).replace("+", "%20");
            String loc = publicDrupal+"/repository/"+encodedFid+"/version/"+timestamp;

            String pid    = pidUri.toString().replaceAll("^http(s?)://hdl.handle.net/","hdl:");
            String prefix = pid.replaceAll("hdl:([^/]*)/.*","$1");
            String uuid   = pid.replaceAll(".*/","");

            logger.info("Update handle["+prefix+"/"+uuid+"] -> archived version URI["+loc+"]");
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
     * Public Drupal origin used in Handle targets.
     *
     * The container environment is preferred so one image/config tree works
     * for development and production. A drupalConfig publicServer value is a
     * fallback for non-container deployments.
     */
    protected String loadPublicDrupalServer() throws DepositException {
        String publicDrupal = System.getenv("DRUPAL_PUBLIC_URL");
        if (publicDrupal == null || publicDrupal.isBlank()) {
            String drupal = this.getParameter("drupalConfig");
            if (drupal != null) {
                File config = new File(drupal);
                if (config.isFile() && config.canRead()) {
                    try {
                        publicDrupal = new XMLConfiguration(config).getString("publicServer");
                    } catch (Exception ex) {
                        throw new DepositException("Couldn't read Drupal configuration["+drupal+"]", ex);
                    }
                }
            }
        }
        if (publicDrupal == null || publicDrupal.isBlank()) {
            throw new DepositException("No public Drupal URL configured; set DRUPAL_PUBLIC_URL or Drupal publicServer");
        }
        return publicDrupal.replaceAll("/+$", "");
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
