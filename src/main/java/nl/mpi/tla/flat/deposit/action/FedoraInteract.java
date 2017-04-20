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
import com.yourmediashelf.fedora.client.response.IngestResponse;
import com.yourmediashelf.fedora.client.response.GetDatastreamResponse;
import com.yourmediashelf.fedora.client.response.ModifyDatastreamResponse;
import java.io.File;
import java.io.FilenameFilter;
import java.util.Collection;
import nl.mpi.tla.flat.deposit.Context;
import nl.mpi.tla.flat.deposit.DepositException;
import nl.mpi.tla.flat.deposit.sip.Resource;
import nl.mpi.tla.flat.deposit.sip.SIPInterface;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.RegexFileFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author menzowi
 */
public class FedoraInteract extends FedoraAction {

    private static final Logger logger = LoggerFactory.getLogger(Deposit.class.getName());

    @Override
    public boolean perform(Context context) throws DepositException {
        try {
            connect(context);

            SIPInterface sip = context.getSIP(); 

            File dir = new File(this.getParameter("dir", "./fox"));
            String pre = this.getParameter("prefix", "lat");
            
            // <fid>.xml (FOXML -> ingest)
            File[] foxs = dir.listFiles(((FilenameFilter)new RegexFileFilter(pre+"[A-Za-z0-9_]+\\.xml")));
            for (File fox:foxs) {
                String fid = fox.getName().replace(".xml","").replace(pre+"_",pre+":").replace("_CMD","");
                String dsid = (fox.getName().endsWith("_CMD.xml")?"CMD":"OBJ");
                logger.debug("FOXML["+fox+"] -> ["+fid+"]");
                IngestResponse iResponse = ingest().format("info:fedora/fedora-system:FOXML-1.1").content(fox).logMessage("Initial ingest").ignoreMime(true).execute();
                GetDatastreamResponse dsResponse = getDatastream(fid,dsid).execute();
                logger.info("Created FedoraObject["+iResponse.getPid()+"]["+iResponse.getLocation()+"]["+dsid+"]["+dsResponse.getLastModifiedDate()+"]");
            }

            // - <fid>.prop (props -> modify (some) properties)
            // TODO
            
            // - <fid>.<dsid>.<ext>... (DS -> modifyDatastream)
            foxs = dir.listFiles(((FilenameFilter)new RegexFileFilter(pre+"[A-Za-z0-9_]+\\.[A-Z]+\\.[A-Za-z0-9_]+")));
            for (File fox:foxs) {
                String fid  = fox.getName().replaceFirst("\\..*$","").replace(pre+"_",pre+":");
                String dsid =  fox.getName().replaceFirst("^.*\\.([A-Z]+)\\..*$","$1");
                logger.debug("DSID["+fox+"] -> ["+fid+"]["+dsid+"]");
                ModifyDatastreamResponse mdsResponse = modifyDatastream(fid,dsid).content(fox).logMessage("Updated "+dsid).execute();
                logger.info("Updated FedoraObject["+fid+"]["+dsid+"]["+mdsResponse.getLastModifiedDate()+"]");
            }
            
            // TODO: get PID and FID+timestamp -> register for upsert
            /*
            Collection<File> foxs = FileUtils.listFiles(new File(this.getParameter("dir", "./fox")),new String[] {"xml"},true);
            logger.debug("Loading ["+foxs.size()+"] FOX files from dir["+this.getParameter("dir", "./fox")+"]");
            for (File fox:foxs) {
                logger.debug("FOX["+fox+"]");
                IngestResponse iResponse = ingest().format("info:fedora/fedora-system:FOXML-1.1").content(fox).logMessage("Initial ingest").ignoreMime(true).execute();
                logger.info("Created FedoraObject["+iResponse.getPid()+"]["+iResponse.getLocation()+"]");
            }
            for (Resource res:sip.getResources()) {
                GetDatastreamResponse dsResponse = getDatastream(res.getFID().toString(),"OBJ").execute();
                res.setFIDStream("OBJ");
                res.setFIDasOfTimeDate(dsResponse.getLastModifiedDate());
            }
            
            GetDatastreamResponse dsResponse = getDatastream(sip.getFID().toString(),"CMD").execute();
            sip.setFIDStream("CMD");
            sip.setFIDasOfTimeDate(dsResponse.getLastModifiedDate());
            */
        } catch(Exception e) {
            throw new DepositException("The actual deposit in Fedora failed!",e);
        }
        return false;
    }
    
}
