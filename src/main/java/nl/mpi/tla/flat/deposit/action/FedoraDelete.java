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

import static com.yourmediashelf.fedora.client.FedoraClient.*;
import com.yourmediashelf.fedora.client.response.FedoraResponse;
import com.yourmediashelf.fedora.client.response.ModifyDatastreamResponse;
import com.yourmediashelf.fedora.client.response.RiSearchResponse;
import java.io.InputStream;
import java.net.URI;
import java.util.Iterator;
import javax.xml.transform.stream.StreamSource;
import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.XdmAtomicValue;
import net.sf.saxon.s9api.XdmDestination;
import net.sf.saxon.s9api.XdmItem;
import net.sf.saxon.s9api.XdmNode;
import net.sf.saxon.s9api.XsltTransformer;
import nl.mpi.tla.flat.deposit.Context;
import nl.mpi.tla.flat.deposit.DepositException;
import nl.mpi.tla.flat.deposit.sip.SIPInterface;
import nl.mpi.tla.flat.deposit.util.Saxon;
import nl.mpi.tla.flat.deposit.util.SaxonListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 *
 * @author menzowi
 */
public class FedoraDelete extends FedoraAction {

    private static final Logger logger = LoggerFactory.getLogger(FedoraDelete.class.getName());

    @Override
    public boolean perform(Context context) throws DepositException {
        try {
            connect(context);

            SIPInterface sip = context.getSIP();
            
            String sid = sip.getFID().toString().replaceAll("@.*","").replaceAll("#.*","");            
            String sparql = "SELECT ?fid WHERE { ?fid <info:fedora/fedora-system:def/relations-external#isConstituentOf> <info:fedora/"+sid+"> } ";
            logger.debug("SPARQL["+sparql+"]");
            RiSearchResponse resp = riSearch(sparql).format("sparql").execute();
            if (resp.getStatus()==200) {
                XdmNode tpl = Saxon.buildDocument(new StreamSource(resp.getEntityInputStream()));
                logger.debug("RESULT["+tpl.toString()+"]");
                
                XsltTransformer delRel = Saxon.buildTransformer(FOXUpdate.class.getResource("/FedoraDelete/delREL.xsl")).load();
                SaxonListener listener = new SaxonListener("FedoraDelete",MDC.get("sip"));
                delRel.setMessageListener(listener);
                delRel.setErrorListener(listener);

                for (Iterator<XdmItem> iter=Saxon.xpathIterator(tpl, "//*:results/*:result/*:fid/normalize-space(@uri)");iter.hasNext();) {
                    XdmItem f = iter.next();
                    if (f!=null && !f.getStringValue().isEmpty()) {
                        URI fid = new URI(f.getStringValue().replace("info:fedora/",""));
                        if (sip.getResourceByFID(fid)!=null)
                            continue;
                        logger.debug("DELETE: Resource["+fid+"]");
                        // remove relation from deleted Resource to SIP from RELS-EXT
                        FedoraResponse res = getDatastreamDissemination(fid.toString(),"RELS-EXT").execute();
                        if (res.getStatus()==200) {
                            InputStream str = res.getEntityInputStream();
                            XdmNode ext = Saxon.buildDocument(new StreamSource(str));
                            delRel.setSource(ext.asSource());
                            delRel.clearParameters();
                            delRel.setParameter(new QName("sip"), new XdmAtomicValue(sid));
                            XdmDestination destination = new XdmDestination();
                            delRel.setDestination(destination);
                            delRel.transform();
                            ModifyDatastreamResponse mdsResponse = modifyDatastream(fid.toString(),"RELS-EXT").content(Saxon.toString(destination.getXdmNode().asSource())).logMessage("Deleted from compound["+sid+"]").execute();
                            if (mdsResponse.getStatus()!=200)
                                throw new DepositException("Unexpected status["+mdsResponse.getStatus()+"] while interacting with Fedora Commons!");
                            logger.debug("DELETE: Resource["+fid+"] deleted from SIP["+sid+"]");
                            // if Resource has no other relation to a SIP set the state to the DO to inactive
                            if (Saxon.xpath2boolean(destination.getXdmNode(),"empty(//*:isConstituentOf)")) {
                                FedoraResponse fResponse = modifyObject(fid.toString()).state("I").execute();
                                if (fResponse.getStatus()!=200)
                                    throw new DepositException("Unexpected status["+fResponse.getStatus()+"] while interacting with Fedora Commons!");
                                logger.debug("DELETE: Resource["+fid+"] set state[Inactive]");
                            }
                        } else
                            throw new DepositException("Unexpected status["+resp.getStatus()+"] while querying Fedora Commons!");
                    }
                }
            } else
                throw new DepositException("Unexpected status["+resp.getStatus()+"] while querying Fedora Commons!");
        } catch(Exception e) {
            throw new DepositException("Connecting to Fedora Commons failed!",e);
        }

        return true;
    }
    
}
