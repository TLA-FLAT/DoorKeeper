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

import org.fcrepo.client.*;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.Date;
import javax.xml.transform.stream.StreamSource;
import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.XdmNode;
import nl.mpi.tla.flat.deposit.Context;
import nl.mpi.tla.flat.deposit.DepositException;
import nl.mpi.tla.flat.deposit.util.Global;
import nl.mpi.tla.util.Saxon;
import org.apache.commons.configuration.XMLConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

abstract public class FedoraAction extends AbstractAction {

    private static final Logger logger = LoggerFactory.getLogger(FedoraAction.class.getName());
    
    private static String user = null;
    
    protected XMLConfiguration fedoraConfig = null;
    
    protected static FcrepoClient fedoraClient = null;
    
    public void connect(Context context) throws DepositException {
        try {
            fedoraConfig = new XMLConfiguration(new File(getParameter("fedoraConfig")));        

            if (fedoraClient == null) {
                user = fedoraConfig.getString("userName");
                String pass = fedoraConfig.getString("userPass");            
                fedoraClient = FcrepoClient.client()/*.credentials(user, pass)*/.build();
            }
            
        } catch(Exception e) {
            throw new DepositException("Connecting to Fedora Commons failed!",e);
        }
    }
    
    public String getFedoraUser() {
        return this.user;
    }
    
    public XdmNode sparql(String query) throws DepositException {
        XdmNode result = null;
        try {
            logger.debug("SPARQL query["+query+"]");
            String endpoint = fedoraConfig.getString("tripleStore");
            logger.debug("SPARQL endpoint["+endpoint+"]");
            HttpClient client = HttpClient.newBuilder()
                .build();
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/sparql-query")
                .POST(BodyPublishers.ofString(query))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
            HttpResponse<InputStream> response = client.send(request, BodyHandlers.ofInputStream());
            if (response.statusCode()==200) {
                result = Saxon.buildDocument(new StreamSource(response.body()));
            } else
                throw new DepositException("Unexpected status["+response.statusCode()+"] while querying the tripple store!");
        } catch(IOException | InterruptedException | SaxonApiException e) {
            throw new DepositException("Connecting to the tripple store failed!",e);
        }
        return result;
    }
    
    public URI lookupFID(URI pid) throws DepositException {
        URI fid = null;
        try {
            String query = "SELECT ?fid WHERE { ?fid <http://purl.org/dc/elements/1.1/identifier> \""+pid.toString().replace("hdl:","https://hdl.handle.net/")+"\" } ";
            XdmNode tpl = sparql(query);
            logger.debug("RESULT["+tpl.toString()+"]");
            String f = Saxon.xpath2string(tpl, "normalize-space(//srx:results/srx:result/srx:binding[@name='fid']/srx:uri)",null,Global.NAMESPACES);
            if (f!=null && !f.isEmpty()) {
                String rest = fedoraConfig.getString("localBase");
                fid = new URI(f.replaceAll(rest+"/",""));
            }
        } catch(URISyntaxException | SaxonApiException e) {
            throw new DepositException(e);
        }
        logger.debug("found["+fid+"]");
        return fid;
    }
    
    public URI lookupPID(URI fid) throws DepositException {
        URI pid = null;
        try {
            String rest = fedoraConfig.getString("localBase");
            String query = "SELECT ?pid WHERE { <"+rest+"/"+fid.toString().replaceAll("#.*","")+"> <http://purl.org/dc/elements/1.1/identifier> ?pid } ";
            XdmNode tpl = sparql(query);
            logger.debug("RESULT["+tpl.toString()+"]");
            String p = Saxon.xpath2string(tpl, "normalize-space(//*:results/*:result/*:pid[starts-with(.,'https://hdl.handle.net/')])");
            if (p!=null && !p.isEmpty())
                pid = new URI(p.replace("https://hdl.handle.net/","hdl:"));
        } catch(URISyntaxException | SaxonApiException e) {
            throw new DepositException(e);
        }
        return pid;
    }
    
    public XdmNode fcrepo(URI fid) throws DepositException {
        XdmNode res = null;
        URI uri = null;
        try {
            uri = new URI(fedoraConfig.getString("localServer")+"/"+fid.toString());
        } catch (Exception e) {
            throw new DepositException(e);   
        }
        logger.debug("FCREPO["+uri.toString()+"]");
        try (FcrepoResponse response = new GetBuilder(uri, fedoraClient)
            .accept("application/rdf+xml")
            .perform()) {
                res = Saxon.buildDocument(new StreamSource(response.getBody()));
                logger.debug("FCREPO response["+res.toString()+"]");
            } catch (Exception e) {
                 throw new DepositException(e);   
            }
        return res;
    }
    
    public Date lookupAsOfDateTime(URI fid) throws DepositException {
        Date res = null;
        try {
            URI uri = new URI(fedoraConfig.getString("localServer")+"/"+fid.toString());
            String date = Saxon.xpath2string(
                    fcrepo(
                            new URI(fid.toString().replaceAll("#.*",""))
                    ),
                    "//rdf:Description[@rdf:about='"+uri.toString()+"']/fedora:lastModified",null,Global.NAMESPACES);
            logger.debug("lookupAsOfDateTime["+date+"]");
            res = Global.asOfDateTime(date);
        } catch(Exception e) {
            throw new DepositException("Connecting to Fedora Commons failed!",e);
        }
        return res;
    }
    
}
