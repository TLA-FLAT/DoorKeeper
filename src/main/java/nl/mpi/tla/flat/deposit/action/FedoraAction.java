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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
    
    private String user = null;
    
    protected XMLConfiguration fedoraConfig = null;
    
    protected FcrepoClient fedoraClient = null;

    private static final String CLIENT_MEMORY_KEY_PREFIX = FedoraAction.class.getName() + ".client:";

    private static final int EXTERNAL_CONTENT_MAX_ATTEMPTS = 121;
    private static final long EXTERNAL_CONTENT_RETRY_DELAY_MS = 250L;
    
    public void connect(Context context) throws DepositException {
        try {
            fedoraConfig = new XMLConfiguration(new File(getParameter("fedoraConfig")));

            String configPath = new File(getParameter("fedoraConfig")).getCanonicalPath();
            String clientKey = CLIENT_MEMORY_KEY_PREFIX + configPath;
            user = fedoraConfig.getString("userName");

            if (context.hasInMemory(clientKey)) {
                fedoraClient = (FcrepoClient) context.getFromMemory(clientKey);
            } else {
                String pass = readSecret(fedoraConfig, "userPass", "userPassFile");
                FcrepoClient.FcrepoClientBuilder builder = FcrepoClient.client();
                if (user != null && !user.isEmpty()) {
                    URI localServer = URI.create(fedoraConfig.getString("localServer"));
                    builder = builder.credentials(user, pass).authScope(localServer.getHost());
                }
                fedoraClient = builder.build();
                context.putInMemory(clientKey, fedoraClient);
            }

        } catch(Exception e) {
            throw new DepositException("Connecting to Fedora Commons failed!",e);
        }
    }
    
    public String getFedoraUser() {
        return this.user;
    }

    /** Read a credential from a mounted secret file, with the inline value kept for compatibility. */
    protected static String readSecret(XMLConfiguration config, String valueKey, String fileKey) throws IOException {
        String secretFile = config.getString(fileKey);
        if (secretFile != null && !secretFile.isBlank()) {
            Path path = Path.of(secretFile);
            if (!Files.isRegularFile(path) || !Files.isReadable(path))
                throw new IOException("Credential file[" + path + "] isn't a readable regular file");
            return stripLineEnding(Files.readString(path, StandardCharsets.UTF_8));
        }
        return config.getString(valueKey);
    }

    private static String stripLineEnding(String value) {
        while (value.endsWith("\n") || value.endsWith("\r"))
            value = value.substring(0, value.length() - 1);
        return value;
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
                .header("Accept", "application/sparql-results+xml")
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
            // dc:identifier may be stored as a literal (legacy/migrated data) or as a URI (current ingest)
            String hdl = Global.asHandleURL(pid).toString();
            String query = "SELECT ?fid WHERE { { ?fid <http://purl.org/dc/elements/1.1/identifier> \""+hdl+"\" } UNION { ?fid <http://purl.org/dc/elements/1.1/identifier> <"+hdl+"> } } ";
            XdmNode tpl = sparql(query);
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
            String p = Saxon.xpath2string(tpl, "normalize-space((//srx:results/srx:result/srx:binding[@name='pid']/*[self::srx:literal or self::srx:uri][starts-with(.,'https://hdl.handle.net/')])[1])",null,Global.NAMESPACES);
            if (p!=null && !p.isEmpty())
                pid = Global.asHandleURL(new URI(p));
        } catch(URISyntaxException | SaxonApiException e) {
            throw new DepositException(e);
        }
        return pid;
    }
    
    public Boolean fcrepo_exists(URI fid,String ds,URI tx) throws DepositException {
        URI uri = fid;
        if (ds!=null && !ds.isBlank() && !ds.isEmpty())
            try {
               uri = new URI(fid.toString()+"/"+ds);
            } catch (Exception e) {
                throw new DepositException(e);
            }
        return fcrepo_exists(uri,tx);
    }

    public Boolean fcrepo_exists(URI fid,URI tx) throws DepositException {
        Boolean res = null;
        URI uri = null;
        try {
            uri = new URI(fedoraConfig.getString("localServer")+"/"+fid.toString());
        } catch (Exception e) {
            throw new DepositException(e);
        }
        logger.debug("FCREPO["+uri.toString()+"]"+(tx!=null?"[tx]":""));
        HeadBuilder req = new HeadBuilder(uri, fedoraClient);
        if (tx!=null) req = req.addTransaction(tx);
        try (FcrepoResponse response = req.perform()) {
                logger.debug("FCREPO code["+response.getStatusCode()+"]");
                res = Boolean.valueOf(response.getStatusCode() == 200);
            } catch (Exception e) {
                 throw new DepositException(e);
            }
        return res;
    }

    public XdmNode fcrepo(URI fid) throws DepositException {
        return fcrepo(fid,null);
    }

    public XdmNode fcrepo(URI fid,URI tx) throws DepositException {
        XdmNode res = null;
        URI uri = null;
        try {
            uri = new URI(fedoraConfig.getString("localServer")+"/"+fid.toString());
        } catch (Exception e) {
            throw new DepositException(e);
        }
        logger.debug("FCREPO["+uri.toString()+"]"+(tx!=null?"[tx]":""));
        GetBuilder req = new GetBuilder(uri, fedoraClient).accept("application/rdf+xml");
        if (tx!=null) req = (GetBuilder)req.addTransaction(tx);
        try (FcrepoResponse response = req.perform()) {
                logger.debug("FCREPO code["+response.getStatusCode()+"]");
                res = Saxon.buildDocument(new StreamSource(response.getBody()));
            } catch (Exception e) {
                 throw new DepositException(e);
            }
        return res;
    }

    /** The Fedora transaction URI stashed in memory by FedoraTransaction, or null when writing outside a transaction. */
    protected URI transURI(Context context) throws DepositException {
        try {
            if (context.hasInMemory("transLocation"))
                return new URI(context.getFromMemory("transLocation").toString());
            logger.warn("No Fedora transaction in memory; write will not be atomic!");
            return null;
        } catch (Exception e) {
            throw new DepositException("Couldn't determine the Fedora transaction URI!", e);
        }
    }

    /** PUT a binary (LDP-NR) child datastream, joining the active transaction. */
    protected void putBinary(Context context, String fid, String ds, InputStream body, String mime) throws DepositException {
        try (body) {
            String rfid = fedoraConfig.getString("localServer")+"/"+fid+"/"+ds;
            logger.debug("PUT binary["+rfid+"]["+mime+"]");
            PutBuilder pb = new PutBuilder(new URI(rfid),fedoraClient);
            URI tx = transURI(context);
            if (tx != null) pb = pb.addTransaction(tx);
            try (FcrepoResponse response = pb.body(body, mime).perform()) {
                logger.debug("FCREPO code["+response.getStatusCode()+"]");
                if (response.getStatusCode() >= 300)
                    throw new DepositException("can't store the binary ["+rfid+"], status["+response.getStatusCode()+"]");
            }
        } catch (DepositException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new DepositException(ex);
        }
    }

    /** PUT an external-content (proxy) binary child datastream, joining the active transaction. */
    protected void putExternal(Context context, String fid, String ds, String ref, String mime) throws DepositException {
        try {
            String rfid = fedoraConfig.getString("localServer")+"/"+fid+"/"+ds;
            logger.debug("PUT external content["+rfid+"] set to ["+ref+"]["+mime+"]");
            URI refUri = new URI(ref);
            URI tx = transURI(context);
            for (int attempt = 1; attempt <= EXTERNAL_CONTENT_MAX_ATTEMPTS; attempt++) {
                PutBuilder pb = new PutBuilder(new URI(rfid),fedoraClient);
                if (tx != null) pb = pb.addTransaction(tx);
                try (FcrepoResponse response = pb.externalContent(refUri, mime, "proxy").perform()) {
                    int status = response.getStatusCode();
                    logger.debug("FCREPO code["+status+"]");
                    if (status < 300)
                        return;

                    // On Docker Desktop a file moved into a shared bind mount can
                    // briefly be invisible in another container. Fedora reports
                    // that allowlist/existence check as HTTP 400. Retry only that
                    // narrowly defined case; all other errors remain immediate.
                    boolean retryable = status == 400
                            && "file".equalsIgnoreCase(refUri.getScheme())
                            && attempt < EXTERNAL_CONTENT_MAX_ATTEMPTS;
                    if (!retryable)
                        throw new DepositException("can't store the external content of ["+rfid+"], status["+status+"]");

                    logger.warn("External file["+ref+"] is not visible to Fedora yet; retrying "
                            + "attempt["+(attempt + 1)+"/"+EXTERNAL_CONTENT_MAX_ATTEMPTS+"]");
                }

                try {
                    Thread.sleep(EXTERNAL_CONTENT_RETRY_DELAY_MS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new DepositException("Interrupted while waiting for external content ["+ref+"]", ex);
                }
            }
        } catch (DepositException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new DepositException(ex);
        }
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
    
    public InputStream getBinaryDataStream(URI fid,String ds) throws DepositException {
        try {
            URI uri = new URI(fedoraConfig.getString("localServer")+"/"+fid.toString()+"/"+ds);
            FcrepoResponse response = new GetBuilder(uri, fedoraClient).perform();
            return response.getBody();
        } catch(Exception e) {
            throw new DepositException("Interacting with Fedora Commons for ["+fid+"]["+ds+"] failed!",e);
        }
    }

    /** GET a datastream and parse it as XML; returns null when the object doesn't have the datastream. */
    public XdmNode getXMLDataStream(URI fid,String dsid) throws DepositException {
        try {
            URI uri = new URI(fedoraConfig.getString("localServer")+"/"+fid.toString()+"/"+dsid);
            logger.debug("FCREPO["+uri.toString()+"]");
            try (FcrepoResponse response = new GetBuilder(uri, fedoraClient).perform()) {
                logger.debug("FCREPO code["+response.getStatusCode()+"]");
                if (response.getStatusCode() == 404 || response.getStatusCode() == 410)
                    return null;
                if (response.getStatusCode() >= 300)
                    throw new DepositException("Unexpected status["+response.getStatusCode()+"] for ["+uri+"]!");
                return Saxon.buildDocument(new StreamSource(response.getBody()));
            }
        } catch(DepositException ex) {
            throw ex;
        } catch(Exception e) {
            throw new DepositException("Interacting with Fedora Commons for ["+fid+"]["+dsid+"] failed!",e);
        }
    }

    public XdmNode getCMDDataStream(URI fid) throws DepositException {
        try {
            URI uri = new URI(fedoraConfig.getString("localServer")+"/"+fid.toString()+"/CMD");
            FcrepoResponse response = new GetBuilder(uri, fedoraClient).perform();
            if (response.getContentType().equals("application/x-cmdi+xml"))
                return Saxon.buildDocument(new StreamSource(response.getBody()));
        } catch(Exception e) {
            throw new DepositException("Interacting with Fedora Commons for ["+fid+"][CMD] failed!",e);
        }
        return null;
    }
}
