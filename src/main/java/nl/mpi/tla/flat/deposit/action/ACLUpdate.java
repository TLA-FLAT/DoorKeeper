/* 
 * Copyright (C) 2015-2018 The Language Archive
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
import static com.yourmediashelf.fedora.client.FedoraClient.getObjectProfile;
import com.yourmediashelf.fedora.client.FedoraClientException;
import com.yourmediashelf.fedora.client.response.FedoraResponse;
import com.yourmediashelf.fedora.client.response.GetObjectProfileResponse;
import java.io.File;
import java.io.InputStream;
import javax.xml.transform.stream.StreamSource;
import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.XdmAtomicValue;
import net.sf.saxon.s9api.XdmDestination;
import net.sf.saxon.s9api.XsltTransformer;
import nl.mpi.tla.flat.deposit.Context;
import nl.mpi.tla.flat.deposit.DepositException;
import nl.mpi.tla.flat.deposit.sip.Resource;
import nl.mpi.tla.flat.deposit.sip.SIPInterface;
import nl.mpi.tla.flat.deposit.util.Global;
import nl.mpi.tla.flat.deposit.util.Saxon;
import nl.mpi.tla.flat.deposit.util.SaxonListener;
import org.apache.commons.io.FileUtils;
import org.apache.jena.ext.com.google.common.io.Files;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 *
 * @author menzowi
 */
public class ACLUpdate extends FedoraAction {
    
    private static final Logger logger = LoggerFactory.getLogger(ACLUpdate.class.getName());
    
    private static XsltTransformer strip = null;

    @Override
    public boolean perform(Context context) throws DepositException {
        try {
            connect(context);

            File dir = new File(getParameter("dir","./acl"));
            if (!dir.exists()) {
                FileUtils.forceMkdir(dir);
            }

            SIPInterface sip = context.getSIP();
            
            // check owner.xml
            File owner = new File(dir + "/owner.xml");
            if (!owner.exists()) {
                String oid = this.getFedoraUser();
                String tpe = "SIP";
                if (sip.isUpdate()) {
                    tpe = "AIP";
                    GetObjectProfileResponse res = getObjectProfile(sip.getFID(true).toString()).execute();
                    if (res.getStatus()==200) {
                        oid = res.getOwnerId();
                    } else
                        throw new DepositException("Unexpected status["+res.getStatus()+"] while querying Fedora Commons!");
                }
                logger.debug("found user["+oid+"] as owner of "+tpe+"["+sip.getFID()+"]");
                XsltTransformer own = Saxon.buildTransformer(FOXUpdate.class.getResource("/ACLUpdate/owner.xsl")).load();
                SaxonListener listener = new SaxonListener("ACLUpdate",MDC.get("sip"));
                own.setMessageListener(listener);
                own.setErrorListener(listener);
                own.setInitialTemplate(new QName("main"));
                own.setParameter(new QName("owner"),new XdmAtomicValue(oid));
                XdmDestination destination = new XdmDestination();
                own.setDestination(destination);
                own.transform();
                Saxon.save(destination,owner);
            }
            
            // check POLICY and RELS-EXT
            if (sip.isUpdate()) {                
                this.check(dir,sip.getFID(true).toString(),"AIP");
                for (Resource res:sip.getResources()) {
                    if (res.isInsert()) {
                        String fid = null;
                        if (res.hasFID())
                            fid = res.getFID(true).toString();
                        else if (res.hasPID())
                            fid = res.getPID().toString().replace("^http(s?)://hdl.handle.net/","hdl:").replace("@format=[a-z]+","").replace("[^a-zA-Z0-9]","_").replace("^hdl_","lat:");
                        File policy = null;
                        if (fid!=null)
                            policy = new File(dir + "/"+fid.replaceAll("[^a-zA-Z0-9]", "_")+".xml");
                        if (policy==null || !policy.exists())
                            logger.warn("new Resource"+(res.hasPID()?"["+res.getPID()+"]":"")+(res.hasFID()?"["+res.getFID()+"]":"")+"["+res.getURI()+"] for AIP["+sip.getFID()+"] has no access policy, will use the default one!");
                    } else if (res.isUpdate())
                        this.check(dir,res.getFID(true).toString(),"existing Resource");
                }
            }
        } catch(Exception e) {
            throw new DepositException("The completion of ACLs for updates failed!",e);
        }
        return true;
    }
    
    void check(File dir,String fid,String tpe) throws Exception {
        // POLICY
        File policy = new File(dir + "/"+fid.replaceAll("[^a-zA-Z0-9]", "_")+".xml");
        if (!policy.exists()) {
            logger.debug("No new POLICY for this "+tpe+"["+fid+"], get existing!");
            try {
                FedoraResponse res = getDatastreamDissemination(fid,"POLICY").execute();
                if (res.getStatus()==200) {
                    InputStream str = res.getEntityInputStream();
                    Saxon.save(new StreamSource(str),policy);
                } else
                    throw new DepositException("Unexpected status["+res.getStatus()+"] while querying Fedora Commons!");
            } catch(FedoraClientException e) {
                if (e.getStatus()==404) {
                    logger.debug("But there is no existing POLICY for this "+tpe+"["+fid+"]!");
                } else
                    throw new DepositException("Unexpected status["+e.getStatus()+"] while querying Fedora Commons!",e);
            }
        }

        // RELS-EXT
        File rels = new File(dir + "/"+fid.replaceAll("[^a-zA-Z0-9]", "_")+".RELS-EXT.xml");
        if (!rels.exists()) {
            logger.debug("No new RELS-EXT for this "+tpe+"["+fid+"], get existing!");
            try {
                FedoraResponse res = getDatastreamDissemination(fid,"RELS-EXT").execute();
                if (res.getStatus()==200) {
                    InputStream str = res.getEntityInputStream();
                    if (strip == null)
                        strip = Saxon.buildTransformer(FOXUpdate.class.getResource("/ACLUpdate/stripRELS-EXT.xsl")).load();
                    SaxonListener listener = new SaxonListener("ACLUpdate",MDC.get("sip"));
                    strip.setMessageListener(listener);
                    strip.setErrorListener(listener);
                    strip.setSource(new StreamSource(str));
                    XdmDestination destination = new XdmDestination();
                    strip.setDestination(destination);
                    strip.transform();
                    Saxon.save(destination,rels);
                } else
                    throw new DepositException("Unexpected status["+res.getStatus()+"] while querying Fedora Commons!");
            } catch(FedoraClientException e) {
                if (e.getStatus()==404) {
                    logger.debug("But there is no existing RELS-EXT for this "+tpe+"["+fid+"]!");
                } else
                    throw new DepositException("Unexpected status["+e.getStatus()+"] while querying Fedora Commons!",e);
            }
        }
    }
    
}
