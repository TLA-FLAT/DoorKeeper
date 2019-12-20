/* 
 * Copyright (C) 2017 The Language Archive
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

import static com.yourmediashelf.fedora.client.FedoraClient.getDatastreams;
import static com.yourmediashelf.fedora.client.FedoraClient.getObjectProfile;
import static com.yourmediashelf.fedora.client.FedoraClient.riSearch;
import com.yourmediashelf.fedora.client.response.RiSearchResponse;
import com.yourmediashelf.fedora.generated.management.DatastreamProfile;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Date;
import java.util.Deque;
import java.util.Iterator;
import javax.xml.transform.stream.StreamSource;
import net.sf.saxon.s9api.XdmItem;
import net.sf.saxon.s9api.XdmNode;
import net.sf.saxon.s9api.XdmValue;
import nl.mpi.tla.flat.deposit.Context;
import nl.mpi.tla.flat.deposit.DepositException;
import nl.mpi.tla.flat.deposit.sip.Collection;
import nl.mpi.tla.flat.deposit.sip.SIPInterface;
import nl.mpi.tla.flat.deposit.sip.cmdi.CMD;
import nl.mpi.tla.flat.deposit.sip.cmdi.CMDCollection;
import nl.mpi.tla.flat.deposit.util.Saxon;
import org.slf4j.LoggerFactory;

/**
 *
 * @author menzowi
 * @author pavsri
 */
public class FedoraLoadCollectionHierarchy extends FedoraAction {
    
    private static final org.slf4j.Logger logger = LoggerFactory.getLogger(FedoraLoadCollectionHierarchy.class.getName());
    
    @Override
    public boolean perform(Context context) throws DepositException {       
        try {
            connect(context);
            
            String namespace = context.getProperty("activeFedoraNamespace", "lat").toString();
            XdmValue namespaces = context.getProperty("fedoraNamespace", "lat");
            
            SIPInterface sip = context.getSIP();
            if (sip.hasCollections()) {
                // check if known collections are complete
                for (Collection col:sip.getCollections()) {
                    if (!col.hasFID()) {
                        if (col.hasPID()) {
                            URI fid = lookupFID(col.getPID());
                            if (fid!=null)
                                col.setFID(fid);
                            else
                                throw new DepositException("Collection["+col.getURI()+"] is not known in the repository!");
                        } else
                            throw new DepositException("Collection["+col.getURI()+"] has no PID or FID!");
                    } else if (!col.hasPID()) {
                        URI pid = lookupPID(col.getFID(true));
                        if (pid!=null)
                            col.setPID(pid);
                        else
                            throw new DepositException("Collection["+col.getURI()+"] is not known in the repository!");
                    }
                    this.completeFID(col);
                }
            } else if (sip.isUpdate() && sip.hasFID()) {
                // fetch collections
                String sparql = "SELECT ?fid WHERE { <info:fedora/"+sip.getFID(true).toString()+"> <info:fedora/fedora-system:def/relations-external#isMemberOfCollection> ?fid } ";
                logger.debug("SPARQL["+sparql+"]");
                RiSearchResponse resp = riSearch(sparql).format("sparql").execute();
                if (resp.getStatus()==200) {
                    XdmNode tpl = Saxon.buildDocument(new StreamSource(resp.getEntityInputStream()));
                    logger.debug("RESULT["+tpl.toString()+"]");
                    for (Iterator<XdmItem> iter=Saxon.xpathIterator(tpl,"normalize-space(//*:results/*:result/*:fid/@uri)");iter.hasNext();) {
                        XdmItem n = iter.next();
                        String f = n.getStringValue();
                        if (f!=null && !f.isEmpty()) {
                            URI fid = new URI(f.replace("info:fedora/",""));
                            if (!fid.toString().startsWith("islandora:")) {
                                URI pid = lookupPID(fid);
                                CMDCollection col = new CMDCollection(pid, fid, namespace, namespaces);
                                if (sip instanceof CMD)
                                    ((CMD)sip).addCollection(col);
                                this.completeFID(col);
                            } else if (!fid.toString().equals("islandora:compound_collection")) {
                                logger.warn("Relationship with collection["+fid+"] might not survive!");
                            } else
                                logger.debug("Skipped islandora:compound_collection relationship!");
                        }
                    }
                } else
                    throw new DepositException("Unexpected status["+resp.getStatus()+"] while querying Fedora Commons!");
            } else
                logger.debug("This SIP["+sip.getBase()+"] has no Collections or FID!");
            for (Collection col:sip.getCollections()) {
                loadParentCollections(new ArrayDeque<>(Arrays.asList(col.getFID())),col, namespace, namespaces);
            }
        } catch (DepositException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new DepositException(ex);
        }        
        return true;
    }
    
    private void loadParentCollections(Deque<URI> hist,Collection col, String namespace, XdmValue namespaces) throws Exception {
        // fetch parent collections
        String sparql = "SELECT ?fid WHERE { <info:fedora/"+col.getFID(true).toString()+"> <info:fedora/fedora-system:def/relations-external#isMemberOfCollection> ?fid } ";
        logger.debug("SPARQL["+sparql+"]");
        RiSearchResponse resp = riSearch(sparql).format("sparql").execute();
        if (resp.getStatus()==200) {
            XdmNode tpl = Saxon.buildDocument(new StreamSource(resp.getEntityInputStream()));
            logger.debug("RESULT["+tpl.toString()+"]");
            for (Iterator<XdmItem> iter=Saxon.xpathIterator(tpl,"normalize-space(//*:results/*:result/*:fid/@uri)");iter.hasNext();) {
                XdmItem n = iter.next();
                String f = n.getStringValue();
                if (f!=null && !f.isEmpty()) {
                    URI fid = new URI(f.replace("info:fedora/","").replaceAll("#.*",""));
                    if (hasCMDDatastream(fid)) {
                        URI pid = lookupPID(fid);
                        CMDCollection pcol = new CMDCollection(pid,fid,namespace,namespaces);
                        this.completeFID(pcol);
                        col.addParentCollection(pcol);
                    }
                }
            }
        } else
            throw new DepositException("Unexpected status["+resp.getStatus()+"] while querying Fedora Commons!");
        // fetch ancestor collections
        for (Collection pcol:col.getParentCollections()) {
            if (hasCMDDatastream(pcol.getFID(true))) {
                if (!hist.contains(pcol.getFID())) {
                    hist.push(col.getFID());
                    loadParentCollections(hist, pcol, namespace, namespaces);
                } else {
                    hist.push(col.getFID());
                    throw new DepositException("(in)direct cycle["+hist+"] for FID["+pcol.getFID()+"]");
                }
            }
        }
    }
    
    protected void completeFID(Collection col) throws DepositException {
        try {
            if (col!=null) {
                URI fid = null;
                if (col.hasFID())
                    fid = col.getFID(true);
                else if (col.hasPID())
                    fid = this.lookupFID(col.getPID());
                else 
                    throw new DepositException("Unknown Collection["+col+"]!");
                if (hasCMDDatastream(fid)) {
                    Date asof = getObjectProfile(fid.toString()).execute().getLastModifiedDate();
                    col.setFIDasOfTimeDate(asof);
                    logger.debug("Fedora Collection datastream["+(col.hasPID()?col.getPID():"")+"]->["+col.getFID()+"]=["+fid+"][CMD]["+asof+"] completed!");
                }
            }
        } catch(Exception e) {
            throw new DepositException("Completing the FID of Collection["+col+"] failed!",e);
        }
    }
    
    protected boolean hasCMDDatastream(URI fid) throws DepositException {
        try {
            for(DatastreamProfile p:getDatastreams(fid.toString()).execute().getDatastreamProfiles()) {
                if (p.getDsID().equals("CMD"))
                    return true;
            }
            return false;
        } catch(Exception e) {
            throw new DepositException("Looking for the CMD datastream of Collection["+fid+"] failed!",e);
        }
    }

}
