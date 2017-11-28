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

import com.yourmediashelf.fedora.client.FedoraClient;
import static com.yourmediashelf.fedora.client.FedoraClient.*;
import com.yourmediashelf.fedora.client.FedoraCredentials;
import com.yourmediashelf.fedora.client.request.FedoraRequest;
import com.yourmediashelf.fedora.client.response.GetDatastreamResponse;
import com.yourmediashelf.fedora.client.response.RiSearchResponse;
import java.io.File;
import java.net.URI;
import java.util.Date;
import javax.xml.transform.stream.StreamSource;
import net.sf.saxon.s9api.XdmNode;
import nl.mpi.tla.flat.deposit.Context;
import nl.mpi.tla.flat.deposit.DepositException;
import nl.mpi.tla.flat.deposit.util.Saxon;
import org.apache.commons.configuration.XMLConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

abstract public class FedoraAction extends AbstractAction {

    private static final Logger logger = LoggerFactory.getLogger(FedoraAction.class.getName());
    
    private static String user = null;
    
    private XMLConfiguration fedoraConfig = null;

    public void connect(Context context) throws DepositException {
        try {
            fedoraConfig = new XMLConfiguration(new File(getParameter("fedoraConfig")));        

            String user = fedoraConfig.getString("userName");
            if (!FedoraRequest.isDefaultClientSet() || this.user==null || this.user!=user ) {
                logger.debug("Fedora Commons["+fedoraConfig.getString("localServer")+"]["+user+":"+fedoraConfig.getString("userPass")+"]");
                FedoraCredentials credentials = new FedoraCredentials(fedoraConfig.getString("localServer"), fedoraConfig.getString("userName"), fedoraConfig.getString("userPass"));
                FedoraClient fedora = new FedoraClient(credentials);
                fedora.debug(this.getParameter("fedoraDebug","false").equals("true"));
                FedoraRequest.setDefaultClient(fedora);
                this.user = user;
            }
            logger.debug("Fedora Commons repository["+FedoraClient.describeRepository().xml(true).execute()+"]");
        } catch(Exception e) {
            throw new DepositException("Connecting to Fedora Commons failed!",e);
        }
    }
    
    public URI lookupFID(URI pid) throws DepositException {
        URI fid = null;
        try {
            String sparql = "SELECT ?fid WHERE { ?fid <http://purl.org/dc/elements/1.1/identifier> \""+pid.toString().replace("hdl:","https://hdl.handle.net/")+"\" } ";
            logger.debug("SPARQL["+sparql+"]");
            RiSearchResponse resp = riSearch(sparql).format("sparql").execute();
            if (resp.getStatus()==200) {
                XdmNode tpl = Saxon.buildDocument(new StreamSource(resp.getEntityInputStream()));
                logger.debug("RESULT["+tpl.toString()+"]");
                String f = Saxon.xpath2string(tpl, "normalize-space(//*:results/*:result/*:fid/@uri)");
                if (f!=null && !f.isEmpty())
                    fid = new URI(f.replace("info:fedora/",""));
            } else
                throw new DepositException("Unexpected status["+resp.getStatus()+"] while querying Fedora Commons!");
        } catch(Exception e) {
            throw new DepositException("Connecting to Fedora Commons failed!",e);
        }
        return fid;
    }
    
    public URI lookupPID(URI fid) throws DepositException {
        URI pid = null;
        try {
            String sparql = "SELECT ?pid WHERE { <info:fedora/"+fid.toString().replaceAll("#.*","")+"> <http://purl.org/dc/elements/1.1/identifier> ?pid } ";
            logger.debug("SPARQL["+sparql+"]");
            RiSearchResponse resp = riSearch(sparql).format("sparql").execute();
            if (resp.getStatus()==200) {
                XdmNode tpl = Saxon.buildDocument(new StreamSource(resp.getEntityInputStream()));
                logger.debug("RESULT["+tpl.toString()+"]");
                String p = Saxon.xpath2string(tpl, "normalize-space(//*:results/*:result/*:pid[starts-with(.,'https://hdl.handle.net/')])");
                if (p!=null && !p.isEmpty())
                    pid = new URI(p.replace("https://hdl.handle.net/","hdl:"));
            } else
                throw new DepositException("Unexpected status["+resp.getStatus()+"] while querying Fedora Commons!");
        } catch(Exception e) {
            throw new DepositException("Connecting to Fedora Commons failed!",e);
        }
        return pid;
    }
    
    public Date lookupAsOfDateTime(URI fid) throws DepositException {
        try {
            return getObjectProfile(fid.toString().replaceAll("#.*","")).execute().getLastModifiedDate();
        } catch(Exception e) {
            throw new DepositException("Connecting to Fedora Commons failed!",e);
        }
    }
    
}
