package nl.knaw.meertens.pid;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.net.ssl.SSLContext;

import net.sf.json.JSONException;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.httpclient.HostConfiguration;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.httpclient.NameValuePair;
import org.apache.commons.httpclient.URI;
import org.apache.commons.httpclient.UsernamePasswordCredentials;
import org.apache.commons.httpclient.auth.AuthScope;
import org.apache.commons.httpclient.methods.DeleteMethod;
//import org.apache.commons.httpclient.contrib.ssl.EasySSLProtocolSocketFactory;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.methods.PutMethod;
import org.apache.commons.httpclient.methods.StringRequestEntity;
import org.apache.commons.httpclient.protocol.Protocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class PIDService {
    
    private static final Logger logger = LoggerFactory.getLogger(PIDService.class.getName());
    
    private final String hostName;
    private final String host;
    private final String handlePrefix;
    private final String userName;
    private final String password;
    private final String email;
    private boolean isTest = true;
    
    private final SSLContext ssl;
	
    public PIDService(SSLContext ssl) throws ConfigurationException{
        this(new XMLConfiguration("config.xml"), ssl);
    }
	
    public PIDService(XMLConfiguration config, SSLContext ssl) throws ConfigurationException{	
        this.ssl = ssl;
        
        if( config == null)
            throw new IllegalArgumentException("No EPIC configuration specified!");
        
        for(Iterator iter =config.getKeys();iter.hasNext();) {
            logger.debug("EPIC configuration key["+iter.next()+"]");
        }

        // do something with config
        this.hostName = config.getString("hostName");
        this.host = config.getString("URI");
        this.handlePrefix = config.getString("HandlePrefix");
        this.userName = config.getString("userName");
        this.password = config.getString("password");
        this.email = config.getString("email");
        this.isTest = config.getString("status") != null && config.getString("status").equals("test");
        
        logger.debug((this.isTest?"test":"production")+" PIDService ["+this.host+"]["+this.handlePrefix+"]["+this.userName+":"+this.password+"]["+this.email+"]");
    }
	
    public String requestHandle(String a_location) throws IOException, HandleCreationException{
        return requestHandle(UUID.randomUUID().toString(), a_location);
    }
    
    public String requestHandle(String uuid,String a_location) throws IOException, HandleCreationException{
		
        if (isTest) {
            logger.info("[TESTMODE] Created Handle=["+"PIDManager_"+ a_location+"] for location["+a_location+"]");
            return "PIDManager_"+ a_location;
        }
		
        Protocol easyhttps = null;
        try {
            easyhttps = new Protocol("https", new EasySSLProtocolSocketFactory(ssl), 443);
        } catch (Exception e){
            logger.error("Problem configurating connection",e);
            throw new IOException("Problem configurating connection");
        }
        String handle = this.handlePrefix + "/" + uuid;
        logger.info("Requesting handle: " + handle);
        URI uri = new URI(host + handle, true);
		
        HttpClient client = new HttpClient();
        client.getState().setCredentials(
            new AuthScope(this.hostName, 443, "realm"),
            new UsernamePasswordCredentials(this.userName, this.password));
        client.getParams().setAuthenticationPreemptive(true);
        PutMethod httpput = new PutMethod( uri.getPathQuery());
        httpput.setRequestHeader("Content-Type", "application/json");
        HostConfiguration hc = new HostConfiguration();
        hc.setHost(uri.getHost(), uri.getPort(), easyhttps);
        httpput.setDoAuthentication(true);
						
        Map<String, Object> map = new HashMap<String, Object>();
        map.put("idx", "1");
        map.put("type", "URL");
        map.put("parsed_data",a_location);
        map.put( "timestamp", "" + System.currentTimeMillis());
        map.put("refs","");
        Map<String, Object> map2 = new HashMap<String, Object>();
        map2.put("idx", "2");
        map2.put("type", "EMAIL");
        map2.put("parsed_data",this.email);
        map2.put( "timestamp", System.currentTimeMillis());
        map2.put("refs","");
        String jsonStr = null;
        try {
            List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
            list.add(map);
            list.add(map2);
            JSONArray a = JSONArray.fromObject(list);
            jsonStr = a.toString();
            logger.info(jsonStr);
        } catch (JSONException e) {
            logger.error("Unable to create JSON Request object",e);
            throw new IOException( "Unable to create JSON Request object");
        }
		
        httpput.setRequestEntity(new StringRequestEntity( jsonStr, "application/json","UTF-8"));
				
        try {
            client.executeMethod(hc, httpput);
            if (httpput.getStatusCode() != HttpStatus.SC_CREATED ) {
                logger.error("EPIC unexpected result[" + httpput.getStatusLine().toString()+"]");
                throw new HandleCreationException("Handle creation failed. Unexpected failure: " + httpput.getStatusLine().toString() + ". " + httpput.getResponseBodyAsString());
            }
	} finally {
            logger.debug("EPIC result["+httpput.getResponseBodyAsString()+"]");
            httpput.releaseConnection();
	}
        
        //A resolvable handle is returned using the global resolver
        logger.info( "Created handle["+handle+"] for location ["+a_location+"]");
		
        return handle;
    }
	
    public void updateLocation( String a_handle, String a_location)throws IOException, HandleCreationException{
        if (isTest) {
            logger.debug("[TESTMODE] Handled request location change for Handle=["+a_handle+"] to new location["+a_location+"] ... did nothing");
            return;
        }
        UUID uuid = UUID.randomUUID();
        Protocol easyhttps = null;
        try {
            easyhttps = new Protocol("https", new EasySSLProtocolSocketFactory(), 443);
	} catch(Exception e){
            logger.error("Problem configurating connection",e);
            throw new IOException("Problem configurating connection");
        }
		
        URI uri = new URI(host + a_handle, true);		
		
        HttpClient client = new HttpClient();
		
        client.getState().setCredentials(
            new AuthScope(this.hostName, 443, "realm"),
            new UsernamePasswordCredentials(this.userName, this.password));
        client.getParams().setAuthenticationPreemptive(true);
        PutMethod httpput = new PutMethod( uri.getPathQuery());
        httpput.setRequestHeader("Content-Type", "application/json");
        HostConfiguration hc = new HostConfiguration();
        hc.setHost(uri.getHost(), uri.getPort(), easyhttps);
        httpput.setDoAuthentication(true);
	
        Map<String, Object> map = new HashMap<String, Object>();
        map.put("idx", "1");
        map.put("type", "URL");
        map.put("parsed_data",a_location);
        map.put( "timestamp", "" + System.currentTimeMillis());
        map.put("refs","");
        Map<String, Object> map2 = new HashMap<String, Object>();
        map2.put("idx", "2");
        map2.put("type", "EMAIL");
        map2.put("parsed_data",this.email);
        map2.put( "timestamp", System.currentTimeMillis());
        map2.put("refs","");
        String jsonStr = null;
        try{
            List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
            list.add(map);
            list.add(map2);
            JSONArray a = JSONArray.fromObject(list);
            jsonStr = a.toString();
            logger.info(jsonStr);
        }
        catch( JSONException e){
            logger.error("Unable to create JSON Request object",e);
            throw new IOException("Unable to create JSON Request object");
        }
		
        //System.out.println( jsonStr);
        httpput.setRequestEntity(new StringRequestEntity( jsonStr, "application/json","UTF-8"));
				
        try {
            client.executeMethod(hc, httpput);
            if (httpput.getStatusCode() == HttpStatus.SC_NO_CONTENT) {
                logger.info( "EPIC updated handle["+a_handle+"] for location ["+a_location+"]");
            } else {
                logger.error("EPIC unexpected result[" + httpput.getStatusLine().toString()+"]");
                throw new HandleCreationException("Handle creation failed. Unexpected failure: " + httpput.getStatusLine().toString() + ". " + httpput.getResponseBodyAsString());
            }
	} finally {
            logger.debug("EPIC result["+httpput.getResponseBodyAsString()+"]");
            httpput.releaseConnection();
	}
    }
	
    public String getPIDLocation( String a_handle) throws IOException{
	Protocol easyhttps = null;
	try {
            easyhttps = new Protocol("https", new EasySSLProtocolSocketFactory(), 443);
	} catch (Exception e){
            logger.error("Problem configurating connection",e);
            throw new IOException("Problem configurating connection");
        }
        URI uri = new URI(host + a_handle, true);
		
        HttpClient client = new HttpClient();
        client.getState().setCredentials(
            new AuthScope(this.hostName, 443, "realm"),
            new UsernamePasswordCredentials(this.userName, this.password));
        client.getParams().setAuthenticationPreemptive(true);
        GetMethod httpGet = new GetMethod(uri.getPathQuery());
        httpGet.setFollowRedirects(false);
        httpGet.setQueryString(new NameValuePair[] { 
            new NameValuePair("redirect", "no") 
        }); 
        httpGet.setRequestHeader("Accept", "application/json");
        HostConfiguration hc = new HostConfiguration();
        hc.setHost(uri.getHost(), uri.getPort(), easyhttps);
        httpGet.setDoAuthentication(true);
        String location = null;
        JSONObject json = null;
        try {
            client.executeMethod(hc, httpGet);
            switch (httpGet.getStatusCode()) {
                case HttpStatus.SC_OK:
                    logger.debug(httpGet.getResponseBodyAsString());
                    JSONArray jsonArr = JSONArray.fromObject(httpGet.getResponseBodyAsString());
                    json = jsonArr.getJSONObject(0);
                    location = json.getString("parsed_data");
                    break;
                case HttpStatus.SC_NOT_FOUND:
                    logger.warn("EPIC handle["+a_handle+"] doesn't exist[" + httpGet.getStatusLine().toString()+"]");
                    break;
                default:
                    logger.error("EPIC unexpected result[" + httpGet.getStatusLine().toString()+"]");
                    throw new IOException("Handle retrieval failed["+a_handle+"]. Unexpected failure: " + httpGet.getStatusLine().toString() + ". " + httpGet.getResponseBodyAsString());
            }
        } finally {
            logger.debug("EPIC result["+httpGet.getResponseBodyAsString()+"]");
            httpGet.releaseConnection();
        }
        return location;		
    }
	
    public URL makeActionable( String a_PID){
        URL url = null;
        try {
            url = new URL( "http://hdl.handle.net/" + a_PID);
        } catch (MalformedURLException e) {
            logger.error("couldn't make PID actionable",e);
            //do nothing
            //null will be returned
        }
        return url;
    }
    
    public void deleteHandle(String a_handle) throws IOException {
	Protocol easyhttps = null;
	try {
            easyhttps = new Protocol("https", new EasySSLProtocolSocketFactory(), 443);
	} catch (Exception e){
            logger.error("Problem configurating connection",e);
            throw new IOException("Problem configurating connection");
        }
        URI uri = new URI(host + a_handle, true);
        System.err.println("DBG: URI["+uri+"]");
		
        HttpClient client = new HttpClient();
        client.getState().setCredentials(
            new AuthScope(this.hostName, 443, "realm"),
            new UsernamePasswordCredentials(this.userName, this.password));
        client.getParams().setAuthenticationPreemptive(true);
        DeleteMethod httpDel = new DeleteMethod();
        httpDel.setFollowRedirects(false);
        httpDel.setQueryString(new NameValuePair[] { 
            new NameValuePair("redirect", "no") 
        }); 
        httpDel.setRequestHeader("Accept", "application/json");
        HostConfiguration hc = new HostConfiguration();
        hc.setHost(uri.getHost(), uri.getPort(), easyhttps);
        httpDel.setDoAuthentication(true);
        try {
            client.executeMethod(hc, httpDel);
            if (httpDel.getStatusCode() != HttpStatus.SC_NO_CONTENT) {
                logger.error("EPIC unexpected result[" + httpDel.getStatusLine().toString()+"]");
                throw new IOException("Handle retrieval failed["+a_handle+"]. Unexpected failure: " + httpDel.getStatusLine().toString() + ". " + httpDel.getResponseBodyAsString());
            }
        } finally {
            logger.debug("EPIC result["+httpDel.getResponseBodyAsString()+"]");
            httpDel.releaseConnection();
        }
    }
    
    public static void main(String[] args) {
        
        try {
        
            if (args.length < 3) {
                System.err.println("java -cp doorkeeper.jar nl.knaw.meertens.pid.PIDService <options>");
                System.err.println();
                System.err.println("new handle   : <path to config> new <suffix>? <uri>");
                System.err.println("get handle   : <path to config> get <prefix/suffix>");
                System.err.println("update handle: <path to config> upd <prefix/suffix> <uri>");
                System.err.println("delete handle: <path to config> del <prefix/suffix>");
                System.err.println("               NOTE: there might be a nodelete policy active!");
                System.err.println();
                System.err.println("batch        : <path to config> csv <FILE.csv>");
                System.err.println("               NOTE: CSV columns: <suffix>,<uri>");
                System.err.println("               NOTE: will do an upsert, i.e., insert for a new suffix");
                System.err.println("                     and an update for an existing suffix");
                System.err.println("               NOTE: to be nice there is a delay of 1 second between the EPIC write requests");
                System.exit(1);
            }
        
            String epic = args[0];
            String action = args[1];
        
            File config = new File(epic);
            if (!config.exists()) {
                System.err.println("The EPIC configuration["+epic+"] doesn't exist!");
                System.exit(2);
            } else if (!config.isFile()) {
                System.err.println("The EPIC configuration["+epic+"] isn't a file!");
                System.exit(2);
            } else if (!config.canRead()) {
                System.err.println("The EPIC configuration["+epic+"] can't be read!");
                System.exit(2);
            }

            XMLConfiguration xml = new XMLConfiguration(config);
            PIDService ps = new PIDService(xml,null);
        
            if (action.equals("new")) {
                String suf = (args.length>3?args[2]:null);
                String uri = (args.length>3?args[3]:args[2]);
                if (uri==null) {
                    System.err.println("new handle: needs a URI!");
                    System.exit(3);
                }
                String hdl = suf==null?ps.requestHandle(uri):ps.requestHandle(suf,uri);
                System.err.println("new handle: "+hdl+" -> "+uri);
                System.out.println(hdl);
            } else if (action.equals("get")) {
                if (args.length<3) {
                    System.err.println("get handle: needs a handle!");
                    System.exit(3);
                }
                String hdl = args[2];
                String uri = ps.getPIDLocation(hdl);
                if (uri != null) {
                    System.err.println("got handle: "+hdl+" -> "+uri);
                    System.out.println(uri);
                } else {
                    System.err.println("get handle: "+hdl+" -> doesn't exist!");
                    System.exit(9);
                }                    
            } else if (action.equals("upd")) {
                if (args.length<4) {
                    System.err.println("update handle: needs a handle and an uri!");
                    System.exit(3);
                }
                String hdl = args[2];
                String uri = args[3];
                ps.updateLocation(hdl,uri);
                String nw = ps.getPIDLocation(hdl);
                if (!nw.equals(uri)) {
                    System.err.println("FATAL: failed to update handle["+hdl+"] to ["+uri+"]! It (still) refers to ["+nw+"].");
                    System.exit(3);
                }
                System.err.println("updated handle: "+hdl+" -> "+uri);                    
            } else if (action.equals("del")) {
                if (args.length<3) {
                    System.err.println("delete handle: needs a handle!");
                    System.exit(3);
                }
                String hdl = args[2];
                ps.deleteHandle(hdl);
                System.err.println("deleted handle: "+hdl);
            } else if (action.equals("csv")) {
                if (args.length<3) {
                    System.err.println("csv action: needs a CSV file!");
                    System.exit(3);
                }
                File csv = new File(args[2]);
                if (!csv.exists()) {
                    System.err.println("csv action: The CSV file["+csv.getAbsolutePath()+"] doesn't exist!");
                    System.exit(3);
                } else if (!csv.isFile()) {
                    System.err.println("csv action: The CSV file["+csv.getAbsolutePath()+"] isn't a file!");
                    System.exit(3);
                } else if (!csv.canRead()) {
                    System.err.println("csv action: The CSV file["+csv.getAbsolutePath()+"] can't be read!");
                    System.exit(3);
                }
                String prefix = xml.getString("HandlePrefix");
                List<String> lines=Files.readAllLines(csv.toPath(), Charset.forName("UTF-8"));
                int l =0;
                for(String line:lines){
                    l++;
                    if (line.startsWith("#"))
                        continue;
                    String[] cols = line.split(",");
                    if (cols.length!=2)
                        System.err.println("ERROR: CSV["+csv.getAbsolutePath()+"]["+l+"] doesn't contain 2 columns!");
                    String suffix = cols[0];
                    String uri = cols[1];
                    String hdl = prefix+"/"+suffix;
                    String loc = ps.getPIDLocation(hdl);
                    if (loc == null) {
                        ps.requestHandle(suffix, uri);
                    } else {
                        ps.updateLocation(hdl, uri);
                    }
                    loc = ps.getPIDLocation(hdl);
                    if (!loc.equals(uri)) {
                        System.err.println("ERROR: CSV["+csv.getAbsolutePath()+"]["+l+"] failed to upsert handle["+hdl+"] to ["+uri+"]! It (still) refers to ["+loc+"].");
                    } else
                        System.err.println("CSV["+csv.getAbsolutePath()+"]["+l+"] new handle: "+hdl+" -> "+loc);
                }
            } else {
                System.err.println("Unknown action!");
                System.exit(4);
            }
        
        } catch(Exception e) {
            System.err.println("FATAL: "+e);
            e.printStackTrace(System.err);
        }
        
    }
}
