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
import net.sf.saxon.s9api.XdmItem;
import net.sf.saxon.s9api.XdmNode;
import net.sf.saxon.s9api.XsltTransformer;
import nl.mpi.tla.flat.deposit.Context;
import nl.mpi.tla.flat.deposit.DepositException;
import nl.mpi.tla.flat.deposit.sip.Collection;
import nl.mpi.tla.flat.deposit.sip.cmdi.CMDCollection;
import static nl.mpi.tla.flat.deposit.util.Global.NAMESPACES;
import nl.mpi.tla.flat.deposit.util.Saxon;
import nl.mpi.tla.flat.deposit.util.SaxonListener;
import org.apache.commons.io.FileUtils;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.w3c.dom.Node;

/**
 *
 * @author menzowi
 */
public class UpdateCollections extends FedoraAction {
    
    private static final org.slf4j.Logger logger = LoggerFactory.getLogger(UpdateCollections.class.getName());
    
    private File dir = null;
    
    @Override
    public boolean perform(Context context) throws DepositException {       
        try {
            connect(context);

            // create the output dir
            dir = new File(getParameter("dir","./fox"));
            if (!dir.exists())
                 FileUtils.forceMkdir(dir);

            // prep the stylesheet
            XsltTransformer add = Saxon.buildTransformer(UpdateCollections.class.getResource("/UpdateCollections/add-to-collection.xsl")).load();
            SaxonListener listener = new SaxonListener("UpdateCollections",MDC.get("sip"));
            add.setMessageListener(listener);
            add.setErrorListener(listener);            
            add.setParameter(new QName("pid"),new XdmAtomicValue(context.getSIP().getPID()));
            add.setParameter(new QName("fid"),new XdmAtomicValue(context.getSIP().getFID()));
            add.setParameter(new QName("prefix"),new XdmAtomicValue(getParameter("prefix")));
            add.setParameter(new QName("new-pid-eval"),new XdmAtomicValue(getParameter("new-pid-eval","true()")));
            // loop over collections
            for (Collection col:context.getSIP().getCollections()) {
                logger.debug("isPartOf collection["+col.getURI()+"]["+(col.hasFID()?col.getFID():"")+"]");
                if (col.hasPID() && col.getPID().equals(context.getSIP().getPID()))
                    throw new DepositException("direct cycle for PID["+col.getPID()+"]");
                else if (col.getURI().equals(context.getSIP().getPID()))
                    throw new DepositException("direct cycle for PID["+col.getURI()+"]");
                else if (col.hasFID() && col.getFID().equals(context.getSIP().getFID()))
                    throw new DepositException("direct cycle for FID["+col.getFID()+"]");
                if (col.hasFID() && col.getFID().toString().startsWith("lat:")) {
                    // load the collection's CMD
                    FedoraResponse res = getDatastreamDissemination(col.getFID().toString(),"CMD").execute();
                    if (res.getStatus()==200) {
                        InputStream str = res.getEntityInputStream();
                        XdmNode old = Saxon.buildDocument(new StreamSource(str));
                        String oldPID = (col.hasPID()?col.getPID().toString():Saxon.xpath2string(old, "/cmd:CMD/cmd:Header/cmd:MdSelfLink",null,NAMESPACES));
                        add.setSource(old.asSource());
                        XdmDestination destination = new XdmDestination();
                        add.setDestination(destination);
                        add.transform();
                        if (!Saxon.xpath2boolean(destination.getXdmNode(), "/null")) {
                            // write to fox dir: <fid>.CMD.xml
                            File out = new File(dir + "/"+col.getFID().toString().replaceAll("[^a-zA-Z0-9\\-]", "_")+".CMD.xml");
                            TransformerFactory.newInstance().newTransformer().transform(destination.getXdmNode().asSource(),new StreamResult(out));
                            logger.info("created CMD["+out.getAbsolutePath()+"]");
                            String newPID = Saxon.xpath2string(destination.getXdmNode(), "/cmd:CMD/cmd:Header/cmd:MdSelfLink",null,NAMESPACES);
                            if (!newPID.equals(oldPID)) {
                                URI pid = new URI(newPID);
                                col.setPID(pid);
                                // update the identifier in the DC
                                updateDC(col.getFID(),pid);
                                // TODO: use the relations instead of cmd:IsPartOfList
                                for (XdmItem coll:Saxon.xpath(old,"/cmd:CMD/cmd:Resources/cmd:IsPartOfList/cmd:IsPartOf",null,NAMESPACES)) {
                                    Node colNode = Saxon.unwrapNode((XdmNode)coll);
                                    Collection par = new CMDCollection(colNode);
                                    col.addParentCollection(par);
                                    if (Saxon.xpath2boolean(coll,"normalize-space(@lat:flatURI)!=''",null,NAMESPACES)) {
                                        par.setFID(new URI(Saxon.xpath2string(coll,"cmd:ResourceRef/@lat:flatURI",null,NAMESPACES)));
                                    }
                                    if (par.getFID().toString().startsWith("lat:")) {
                                        updateCollection(new ArrayDeque<>(Arrays.asList(col.getFID())), par, oldPID, newPID);
                                    }
                                }
                            } else
                                col.setPID(new URI(oldPID));
                        } else {
                            logger.debug("Already a member of collection["+col.getFID()+"]!");
                        }
                    } else {
                        logger.debug("Collection["+col.getFID()+"] status["+res.getStatus()+"] has no CMD datastream.");
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
    
    private XsltTransformer upd = null;
    
    private void updateCollection(Deque<URI> hist, Collection col, String oldPart, String newPart) throws Exception {
        // load the collection's CMD
        FedoraResponse res = getDatastreamDissemination(col.getFID().toString(),"CMD").execute();
        if (res.getStatus()==200) {
            // prep the stylesheet
            if (upd == null) {
                XsltTransformer upd = Saxon.buildTransformer(UpdateCollections.class.getResource("/UpdateCollections/update-collection.xsl")).load();
                SaxonListener listener = new SaxonListener("UpdateCollections",MDC.get("sip"));
                upd.setMessageListener(listener);
                upd.setErrorListener(listener);
            }
               
            // set parameters
            upd.setParameter(new QName("old-pid"),new XdmAtomicValue(oldPart));
            upd.setParameter(new QName("new-pid"),new XdmAtomicValue(newPart));
            upd.setParameter(new QName("prefix"),new XdmAtomicValue(getParameter("prefix")));
            upd.setParameter(new QName("new-pid-eval"),new XdmAtomicValue(getParameter("new-pid-eval","true()")));

            InputStream str = res.getEntityInputStream();
            XdmNode old = Saxon.buildDocument(new StreamSource(str));
            String oldPID = Saxon.xpath2string(old, "/cmd:CMD/cmd:Header/cmd:MdSelfLink",null,NAMESPACES);
            upd.setSource(old.asSource());
            XdmDestination destination = new XdmDestination();
            upd.setDestination(destination);
            upd.transform();
            if (!Saxon.xpath2boolean(destination.getXdmNode(), "/null")) {
                // write to fox dir: <fid>.CMD.xml
                File out = new File(dir + "/"+col.getFID().toString().replaceAll("[^a-zA-Z0-9\\-]", "_")+".CMD.xml");
                TransformerFactory.newInstance().newTransformer().transform(destination.getXdmNode().asSource(),new StreamResult(out));
                logger.info("created CMD["+out.getAbsolutePath()+"]");
                String newPID = Saxon.xpath2string(destination.getXdmNode(), "/cmd:CMD/cmd:Header/cmd:MdSelfLink",null,NAMESPACES);
                if (!newPID.equals(oldPID)) {
                    URI pid = new URI(newPID);
                    col.setPID(pid);
                    // update the identifier in the DC
                    updateDC(col.getFID(),pid);
                    // update the parent collection
                    for (Collection pcol:col.getParentCollections()) {
                        if (pcol.getFID().toString().startsWith("lat:")) {
                            if (!hist.contains(pcol.getFID())) {
                                hist.push(col.getFID());
                                updateCollection(hist, pcol, oldPID, newPID);
                            } else {
                                hist.push(col.getFID());
                                throw new DepositException("(in)direct cycle["+hist+"] for FID["+pcol.getFID()+"]");
                            }
                        }
                    }
                } else
                    col.setPID(new URI(oldPID));
            } else {
                logger.debug("Part["+oldPart+"]["+newPart+"] is not a member of collection["+col.getFID()+"]!");
            }
        } else {
            logger.debug("Collection["+col.getFID()+"] status["+res.getStatus()+"] has no CMD datastream.");
        }
    }
    
    private XsltTransformer dc = null;
    
    private void updateDC(URI fid, URI pid) throws FedoraClientException, SaxonApiException, TransformerConfigurationException, TransformerException {
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
                // write to fox dir: <fid>.DC.xml
                File out = new File(dir + "/"+fid.toString().replaceAll("[^a-zA-Z0-9\\-]", "_")+".DC.xml");
                TransformerFactory.newInstance().newTransformer().transform(destination.getXdmNode().asSource(),new StreamResult(out));
            }
        }
    }
    
}
