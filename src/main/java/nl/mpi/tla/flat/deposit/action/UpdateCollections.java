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

import static com.yourmediashelf.fedora.client.FedoraClient.getDatastreamDissemination;
import com.yourmediashelf.fedora.client.FedoraClientException;
import com.yourmediashelf.fedora.client.response.FedoraResponse;
import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Date;
import java.util.Deque;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.XdmAtomicValue;
import net.sf.saxon.s9api.XdmDestination;
import net.sf.saxon.s9api.XdmNode;
import net.sf.saxon.s9api.XsltTransformer;
import nl.mpi.tla.flat.deposit.Context;
import nl.mpi.tla.flat.deposit.DepositException;
import nl.mpi.tla.flat.deposit.sip.Collection;
import nl.mpi.tla.flat.deposit.util.Global;
import static nl.mpi.tla.flat.deposit.util.Global.NAMESPACES;
import nl.mpi.tla.flat.deposit.util.Saxon;
import nl.mpi.tla.flat.deposit.util.SaxonListener;
import org.apache.commons.io.FileUtils;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 *
 * @author menzowi
 */
public class UpdateCollections extends FedoraAction {
    
    private static final org.slf4j.Logger logger = LoggerFactory.getLogger(UpdateCollections.class.getName());
    
    private File dir = null;
    private File first = null;
    
    private XsltTransformer upsert = null;
    
    @Override
    public boolean perform(Context context) throws DepositException {       
        try {
            connect(context);

            // create the output dir
            dir = new File(getParameter("dir","./fox"));
            if (!dir.exists())
                 FileUtils.forceMkdir(dir);
            
            if (this.hasParameter("firstDir")) {
                first = new File(getParameter("firstDir"));
                if (!first.exists())
                     FileUtils.forceMkdir(first);
            } else
                first = dir;
                

            // prep the stylesheet
            upsert = Saxon.buildTransformer(UpdateCollections.class.getResource("/UpdateCollections/upsert-collection.xsl")).load();
            SaxonListener listener = new SaxonListener("UpdateCollections",MDC.get("sip"));
            upsert.setMessageListener(listener);
            upsert.setErrorListener(listener);            
            upsert.setParameter(new QName("fid"),new XdmAtomicValue(context.getSIP().getFID()));
            upsert.setParameter(new QName("new-pid"),new XdmAtomicValue(context.getSIP().getPID()));
            if (context.getSIP().isUpdate()) {
                upsert.setParameter(new QName("old-pid"),new XdmAtomicValue(this.lookupPID(context.getSIP().getFID(true))));
            }
            upsert.setParameter(new QName("prefix"),new XdmAtomicValue(getParameter("prefix")));
            upsert.setParameter(new QName("new-pid-eval"),new XdmAtomicValue(getParameter("new-pid-eval","true()")));
            if (this.hasParameter("try-fix-pid")) {
                logger.warn("Enabled 'trying to fix a broken PID in a collection', never do this in production!");
                upsert.setParameter(new QName("try-fix-pid"),new XdmAtomicValue(this.getParameter("try-fix-pid").toLowerCase().contains("t")));
            }
            // loop over collections
            for (Collection col:context.getSIP().getCollections(false)) {
                logger.debug("isPartOf collection["+col.getURI()+"]["+(col.hasFID()?col.getFID():"")+"]");
                if (col.hasPID() && col.getPID().equals(context.getSIP().getPID()))
                    throw new DepositException("direct cycle for PID["+col.getPID()+"]");
                else if (col.getURI().equals(context.getSIP().getPID()))
                    throw new DepositException("direct cycle for PID["+col.getURI()+"]");
                else if (col.hasFID() && col.getFID().equals(context.getSIP().getFID()))
                    throw new DepositException("direct cycle for FID["+col.getFID()+"]");
                if (col.hasFID() && col.getFID().toString().startsWith("lat:")) {
                    try {
                        // load the collection's CMD
                        FedoraResponse res = getDatastreamDissemination(col.getFID(true).toString(),"CMD").execute();
                        if (res.getStatus()==200) {
                            InputStream str = res.getEntityInputStream();
                            XdmNode old = Saxon.buildDocument(new StreamSource(str));
                            String oldPID = (col.hasPID()?col.getPID().toString():Saxon.xpath2string(old, "/cmd:CMD/cmd:Header/cmd:MdSelfLink",null,NAMESPACES));
                            upsert.setSource(old.asSource());
                            XdmDestination destination = new XdmDestination();
                            upsert.setDestination(destination);
                            upsert.transform();
                            // write to fox dir: <fid>.CMD.<asof>.xml
                            long asof = Global.asOfDateTime(col.getFID().toString().replaceFirst(".*@","")).getTime();
                            logger.debug("collection["+col.getFID()+"]["+asof+"]["+new Date(asof)+"]");
                            File out = new File(first + "/"+col.getFID(true).toString().replaceAll("[^a-zA-Z0-9\\-]", "_")+".CMD."+asof+".xml");
                            TransformerFactory.newInstance().newTransformer().transform(destination.getXdmNode().asSource(),new StreamResult(out));
                            logger.info("created CMD["+out.getAbsolutePath()+"]");
                            String newPID = Saxon.xpath2string(destination.getXdmNode(), "/cmd:CMD/cmd:Header/cmd:MdSelfLink",null,NAMESPACES);
                            if (!newPID.equals(oldPID)) {
                                URI pid = new URI(newPID);
                                col.setPID(pid);
                                // update the identifier in the DC
                                updateDC(first,col.getFID(true),asof,pid);
                                // loop over collections
                                for (Collection par:col.getParentCollections()) {
                                    if (par.getFID().toString().startsWith("lat:")) {
                                        updateCollection(new ArrayDeque<>(Arrays.asList(col.getFID())), par, col.getFID(), oldPID, newPID);
                                    }
                                }
                            } else
                                col.setPID(new URI(oldPID));
                        } else
                            throw new DepositException("Unexpected status["+res.getStatus()+"] while querying Fedora Commons!");
                    } catch(FedoraClientException e) {
                        if (e.getStatus()==404) {
                            logger.debug("Collection["+col.getFID()+"] status["+e.getStatus()+"] has no CMD datastream.");                            
                        } else
                            throw new DepositException("Unexpected status["+e.getStatus()+"] while querying Fedora Commons!",e);
                    }
                } else {
                    logger.debug("Collection["+col.getURI()+"] skipped: "+(col.hasFID()?"no lat FID":"unknown FID")+"!");
                }
            }
        } catch (Exception ex) {
            throw new DepositException(ex);
        }        
        return true;
    }
    
    private void updateCollection(Deque<URI> hist, Collection col, URI fidPart, String oldPart, String newPart) throws Exception {
        try {
            // load the collection's CMD
            FedoraResponse res = getDatastreamDissemination(col.getFID(true).toString(),"CMD").execute();
            if (res.getStatus()==200) {
                // set parameters
                upsert.clearParameters();
                upsert.setParameter(new QName("fid"),new XdmAtomicValue(fidPart));
                upsert.setParameter(new QName("old-pid"),new XdmAtomicValue(oldPart));
                upsert.setParameter(new QName("new-pid"),new XdmAtomicValue(newPart));
                upsert.setParameter(new QName("prefix"),new XdmAtomicValue(getParameter("prefix")));
                upsert.setParameter(new QName("new-pid-eval"),new XdmAtomicValue(getParameter("new-pid-eval","true()")));
                if (this.hasParameter("try-fix-pid"))
                    upsert.setParameter(new QName("try-fix-pid"),new XdmAtomicValue(this.getParameter("try-fix-pid").toLowerCase().contains("t")));

                InputStream str = res.getEntityInputStream();
                XdmNode old = Saxon.buildDocument(new StreamSource(str));
                String oldPID = Saxon.xpath2string(old, "replace(/cmd:CMD/cmd:Header/cmd:MdSelfLink,'http(s)?://hdl.handle.net/','hdl:')",null,NAMESPACES);
                upsert.setSource(old.asSource());
                XdmDestination destination = new XdmDestination();
                upsert.setDestination(destination);
                upsert.transform();
                // write to fox dir: <fid>.CMD.<asof>.xml
                // long asof = Global.asOfDateTime(col.getFID().toString().replaceFirst(".*@","")).getTime();
                // File out = new File(dir + "/"+col.getFID(true).toString().replaceAll("[^a-zA-Z0-9\\-]", "_")+".CMD."+asof+".xml");
                // write to fox dir: <fid>.CMD.xml
                File out = new File(dir + "/"+col.getFID(true).toString().replaceAll("[^a-zA-Z0-9\\-]", "_")+".CMD.xml");
                Saxon.save(destination,out);
                logger.info("created CMD["+out.getAbsolutePath()+"]");
                String newPID = Saxon.xpath2string(destination.getXdmNode(), "replace(/cmd:CMD/cmd:Header/cmd:MdSelfLink,'http(s)?://hdl.handle.net/','hdl:')",null,NAMESPACES);
                if (!newPID.equals(oldPID)) {
                    URI pid = new URI(newPID);
                    col.setPID(pid);
                    // update the identifier in the DC
                    //updateDC(dir,col.getFID(true),asof,pid);
                    updateDC(dir,col.getFID(true),pid);
                    // update the parent collection
                    for (Collection par:col.getParentCollections()) {
                        if (par.getFID().toString().startsWith("lat:")) {
                            if (!hist.contains(par.getFID())) {
                                hist.push(col.getFID());
                                updateCollection(hist, par, col.getFID(), oldPID, newPID);
                            } else {
                                hist.push(col.getFID());
                                throw new DepositException("(in)direct cycle["+hist+"] for FID["+par.getFID()+"]");
                            }
                        }
                    }
                } else
                    col.setPID(new URI(oldPID));
            } else {
                throw new DepositException("Unexpected status["+res.getStatus()+"] while querying Fedora Commons!");
            }
        } catch(FedoraClientException e) {
            if (e.getStatus()==404)
                logger.debug("Collection["+col.getFID()+"] status["+e.getStatus()+"] has no CMD datastream.");
            else
                throw new DepositException("Unexpected status["+e.getStatus()+"] while querying Fedora Commons!",e);
        }
    }
    
    private XsltTransformer dc = null;
    
    private void updateDC(File fox, URI fid, URI pid) throws FedoraClientException, SaxonApiException, TransformerConfigurationException, TransformerException, DepositException {
        updateDC(fox,fid,null,pid);
    }

    private void updateDC(File fox, URI fid, Long asof, URI pid) throws FedoraClientException, SaxonApiException, TransformerConfigurationException, TransformerException, DepositException {
        FedoraResponse res = getDatastreamDissemination(fid.toString(),"DC").execute();
        if (res.getStatus()==200) {
            InputStream str = res.getEntityInputStream();
            XdmNode old = Saxon.buildDocument(new StreamSource(str));
            if (dc == null) {
                dc = Saxon.buildTransformer(UpdateCollections.class.getResource("/UpdateCollections/update-dc.xsl")).load();
                SaxonListener listener = new SaxonListener("UpdateCollections",MDC.get("sip"));
                dc.setMessageListener(listener);
                dc.setErrorListener(listener);
            }
            dc.setSource(old.asSource());
            XdmDestination destination = new XdmDestination();
            dc.setDestination(destination);
            dc.setParameter(new QName("new-pid"),new XdmAtomicValue(pid.toString()));
            dc.transform();
            if (!Saxon.xpath2boolean(destination.getXdmNode(), "/null")) {
                File out = null;
                if (asof!=null) {
                    // write to fox dir: <fid>.DC.<asof>.xml
                    out = new File(fox + "/"+fid.toString().replaceAll("[^a-zA-Z0-9\\-]", "_")+".DC."+asof+".xml");
                } else {
                    // write to fox dir: <fid>.DC.xml
                    out = new File(fox + "/"+fid.toString().replaceAll("[^a-zA-Z0-9\\-]", "_")+".DC.xml");
                }
                TransformerFactory.newInstance().newTransformer().transform(destination.getXdmNode().asSource(),new StreamResult(out));
            }
        } else
            throw new DepositException("Unexpected status["+res.getStatus()+"] while querying Fedora Commons!");
    }
    
}
