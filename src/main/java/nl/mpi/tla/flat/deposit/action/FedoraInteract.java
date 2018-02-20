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
import com.yourmediashelf.fedora.client.response.IngestResponse;
import com.yourmediashelf.fedora.client.response.GetDatastreamResponse;
import com.yourmediashelf.fedora.client.response.ModifyDatastreamResponse;
import java.io.File;
import java.io.FilenameFilter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import javax.xml.transform.stream.StreamSource;
import net.sf.saxon.s9api.XdmItem;
import net.sf.saxon.s9api.XdmNode;
import nl.mpi.tla.flat.deposit.Context;
import nl.mpi.tla.flat.deposit.DepositException;
import nl.mpi.tla.flat.deposit.sip.Collection;
import nl.mpi.tla.flat.deposit.sip.Resource;
import nl.mpi.tla.flat.deposit.sip.SIPInterface;
import static nl.mpi.tla.flat.deposit.util.Global.NAMESPACES;
import nl.mpi.tla.flat.deposit.util.Saxon;
import org.apache.commons.io.filefilter.RegexFileFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author menzowi
 */
public class FedoraInteract extends FedoraAction {

    private static final Logger logger = LoggerFactory.getLogger(FedoraInteract.class.getName());

    @Override
    public boolean perform(Context context) throws DepositException {
        try {
            connect(context);

            SIPInterface sip = context.getSIP(); 

            File dir = new File(this.getParameter("dir", "./fox"));
            String pre = this.getParameter("namespace", "lat");
            
            // <fid>.xml (FOXML -> ingest)
            File[] foxs = dir.listFiles(((FilenameFilter)new RegexFileFilter(pre+"[A-Za-z0-9_]+\\.xml")));
            for (File fox:foxs) {
                String fid = fox.getName().replace(".xml","").replace(pre+"_",pre+":").replace("_CMD","");
                String dsid = (fox.getName().endsWith("_CMD.xml")?"CMD":"OBJ");
                logger.debug("FOXML["+fox+"] -> ["+fid+"]");
                IngestResponse iResponse = ingest().format("info:fedora/fedora-system:FOXML-1.1").content(fox).logMessage("Initial ingest").ignoreMime(true).execute();
                if (iResponse.getStatus()!=201)
                    throw new DepositException("Unexpected status["+iResponse.getStatus()+"] while interacting with Fedora Commons!");
                Date asof = getObjectProfile(fid).execute().getLastModifiedDate();
                completeFID(sip,new URI(fid),asof);
                logger.info("Created FedoraObject["+iResponse.getPid()+"]["+iResponse.getLocation()+"]["+dsid+"]["+asof+"]");
            }

            // - <fid>.<asof>.props (props -> modify (some) properties)
            foxs = dir.listFiles(((FilenameFilter)new RegexFileFilter(pre+"[A-Za-z0-9_]+\\.[0-9]+\\.props")));
            for (File fox:foxs) {
                String fid  = fox.getName().replaceFirst("\\..*$","").replace(pre+"_",pre+":").replace("_CMD","");
                String epoch = fox.getName().replaceFirst("^.*\\.([0-9]+)\\.props$","$1");
                Date asof = new Date(Long.parseLong(epoch));
                logger.debug("Properties["+fox+"] -> ["+fid+"]["+epoch+"="+asof+"]");
                XdmNode props = Saxon.buildDocument(new StreamSource(fox));
                for (Iterator<XdmItem> iter=Saxon.xpathIterator(props, "//foxml:property", null, NAMESPACES);iter.hasNext();) {
                    XdmItem prop = iter.next();
                    String name  = Saxon.xpath2string(prop, "@NAME");
                    String value  = Saxon.xpath2string(prop, "@VALUE");
                    if (name.equals("info:fedora/fedora-system:def/model#label")) {
                        FedoraResponse res = modifyObject(fid).lastModifiedDate(asof).label(value).execute();
                        if (res.getStatus()!=200)
                            throw new DepositException("Unexpected status["+res.getStatus()+"] while interacting with Fedora Commons!");
                    }
                }
            }
                        
            // - <fid>.<dsid>.<asof>.file ... (DS -> modifyDatastream.dsLocation)
            // - <fid>.<dsid>.<asof>.<ext>... (DS -> modifyDatastream.content)
            foxs = dir.listFiles(((FilenameFilter)new RegexFileFilter(pre+"[A-Za-z0-9_]+\\.[A-Z\\-]+\\.[0-9]+\\.[A-Za-z0-9_]+")));
            for (File fox:foxs) {
                String fid  = fox.getName().replaceFirst("\\..*$","").replace(pre+"_",pre+":").replace("_CMD","");
                String dsid = fox.getName().replaceFirst("^.*\\.([A-Z\\-]+)\\..*$","$1");
                String epoch = fox.getName().replaceFirst("^.*\\.([0-9]+)\\..*$","$1");
                Date asof = new Date(Long.parseLong(epoch));
                String ext  = fox.getName().replaceFirst("^.*\\.(.*)$","$1");
                logger.debug("DSID["+fox+"] -> ["+fid+"]["+dsid+"]["+epoch+"="+asof+"]["+ext+"]");
                ModifyDatastreamResponse mdsResponse = null;
                if (ext.equals("file")) {
                    List<String> lines = Files.readAllLines(fox.toPath(),StandardCharsets.UTF_8);
                    if (lines.size()!=1)
                        throw new DepositException("Datastream location file["+fox+"] should contain exactly one line!");
                    mdsResponse = modifyDatastream(fid,dsid).lastModifiedDate(asof).dsLocation(lines.get(0)).logMessage("Updated "+dsid).execute();
                } else if (dsid.equals("CMD")) {
                    mdsResponse = modifyDatastream(fid,dsid).lastModifiedDate(asof).content(fox).mimeType("application/x-cmdi+xml").logMessage("Updated "+dsid).execute();
                } else {
                    mdsResponse = modifyDatastream(fid,dsid).lastModifiedDate(asof).content(fox).logMessage("Updated "+dsid).execute();
                }
                if (mdsResponse.getStatus()!=200)
                    throw new DepositException("Unexpected status["+mdsResponse.getStatus()+"] while interacting with Fedora Commons!");
                logger.info("Updated FedoraObject["+fid+"]["+dsid+"]["+mdsResponse.getLastModifiedDate()+"]");
                // we should update the PID asOfDateTime
                completeFID(sip,new URI(fid),mdsResponse.getLastModifiedDate());
            }
        } catch(Exception e) {
            throw new DepositException("The actual deposit in Fedora failed!",e);
        }
        return true;
    }
    
    protected boolean completeFID(SIPInterface sip, URI fid, Date date) throws DepositException {
        if (sip.hasFID() && sip.getFID().toString().startsWith(fid.toString())) {
            sip.setFIDasOfTimeDate(date); // will keep the latest asOfDateTime
            logger.debug("Fedora SIP datastream["+sip.getPID()+"]->["+sip.getFID()+"]=["+fid+"]["+date+"] completed!");
            return true;
        }
        Collection col = sip.getCollectionByFID(fid);
        if (col!=null) {
            col.setFIDasOfTimeDate(date); // will keep the latest asOfDateTime
            logger.debug("Fedora Collection datastream["+col.getPID()+"]->["+col.getFID()+"]=["+fid+"]["+date+"] completed!");
            return true;
        }
        Resource res = sip.getResourceByFID(fid);
        if (res!=null) {
            res.setFIDasOfTimeDate(date); // will keep the latest asOfDateTime
            logger.debug("Fedora Resource datastream["+res.getPID()+"]->["+res.getFID()+"]=["+fid+"]["+date+"] completed!");
            return true;
        }
        logger.debug("Fedora datastream["+fid+"]["+date+"] couldn't be associated with a PID!");
        return false;
    }
    
}
