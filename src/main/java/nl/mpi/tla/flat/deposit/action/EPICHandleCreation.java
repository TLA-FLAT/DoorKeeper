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

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import nl.knaw.meertens.pid.PIDService;
import nl.mpi.tla.flat.deposit.Context;
import nl.mpi.tla.flat.deposit.DepositException;
import nl.mpi.tla.flat.deposit.sip.Collection;
import nl.mpi.tla.flat.deposit.sip.Resource;
import nl.mpi.tla.flat.deposit.util.Saxon;

import org.apache.commons.configuration.XMLConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.sf.saxon.s9api.XdmItem;

/**
 *
 * @author menzowi
 * @author pavsri
 */
public class EPICHandleCreation extends AbstractAction {
    
    private static final Logger logger = LoggerFactory.getLogger(EPICHandleCreation.class.getName());

    @Override
    public boolean perform(Context context) throws DepositException {
        
        try {
            
        	String namespace = context.getProperty("fedoraNamespace", "lat").toString();
        	
            String fedora = this.getParameter("fedoraConfig");
            String epic   = this.getParameter("epicConfig");

            if (epic == null) {
                logger.error("No EPIC configuration has been specified! Use the epicConfig parameter.");
                return false;
            }

            File fconfig = new File(fedora);
            if (!fconfig.exists()) {
                logger.error("The Fedora configuration["+fedora+"] doesn't exist!");
                return false;
            } else if (!fconfig.isFile()) {
                logger.error("The Fedora configuration["+fedora+"] isn't a file!");
                return false;
            } else if (!fconfig.canRead()) {
                logger.error("The Fedora configuration["+fedora+"] can't be read!");
                return false;
            }
            logger.debug("Fedora configuration["+fconfig.getAbsolutePath()+"]");

            String server = (new XMLConfiguration(fconfig)).getString("publicServer");

            File config = new File(epic);
            if (!config.exists()) {
                logger.error("The EPIC configuration["+epic+"] doesn't exist!");
                return false;
            } else if (!config.isFile()) {
                logger.error("The EPIC configuration["+epic+"] isn't a file!");
                return false;
            } else if (!config.canRead()) {
                logger.error("The EPIC configuration["+epic+"] can't be read!");
                return false;
            }
            logger.debug("EPIC configuration["+config.getAbsolutePath()+"]");
            
            XMLConfiguration xConfig = new XMLConfiguration(config);
            
            boolean isTest = xConfig.getString("status") != null && xConfig.getString("status").equals("test");

            PIDService ps = new PIDService(xConfig, null);
            
            if (context.getSIP().hasPID() && context.getSIP().hasFID()) {

                String fid = context.getSIP().getFID().toString().replaceAll("#.*","");
                String frag   = context.getSIP().getFID().getRawFragment();
                if (frag == null)
                    throw new DepositException("SIP FID["+context.getSIP().getFID()+"] isn't complete!");
                String dsid = frag.replaceAll("@.*","");
                String asof = frag.replaceAll(".*@","");

                URI    pid  = context.getSIP().getPID();
                String uuid = pid.toString().replaceAll(".*/","");
                String loc  = server+"/objects/"+fid+"/datastreams/"+dsid+"/content?asOfDateTime="+asof;

                logger.info("Create handle["+pid+"]["+uuid+"] -> URI["+loc+"]");
                context.registerRollbackEvent(this, "epic creation", "uuid", uuid, "loc", loc);
                String hdl = ps.requestHandle(uuid, loc);
                logger.info("Created handle["+hdl+"] -> URI["+loc+"]");
            } else {
                if (!context.getSIP().hasPID())
                    logger.debug("SIP has no PID!");
                if (!context.getSIP().hasFID())
                    logger.debug("SIP has no FID!");
            }

            for (Collection col:context.getSIP().getCollections(true)) {
                if (col.hasPID() && col.hasFID()) {
                    String fid    = col.getFID().toString().replaceAll("#.*","");
                    String frag   = col.getFID().getRawFragment();
                    if (frag == null) {
                        logger.warn("collection FID["+col.getFID()+"] PID["+col.getPID()+"] isn't updated!");
                        continue;
                    }
                    String dsid   = frag.replaceAll("@.*","");
                    String asof   = frag.replaceAll(".*@","");

                    String pid    = col.getPID().toString().replaceAll("^http(s?)://hdl.handle.net/","hdl:");
                    String prefix = pid.replaceAll("hdl:([^/]*)/.*","$1");
                    String uuid   = pid.replaceAll(".*/","");
                    
                    String loc    = server+"/objects/"+fid+"/datastreams/"+dsid+"/content?asOfDateTime="+asof;

                    logger.info("Lookup handle["+prefix+"/"+uuid+"]");
                    String cur    = (isTest?null:ps.getPIDLocation(prefix+"/"+uuid));
                    logger.info("Looked up handle["+prefix+"/"+uuid+"] -> URI["+cur+"]");
                    
                    if (cur == null) {
                        logger.info("Create handle["+pid+"]["+uuid+"] -> URI["+loc+"]");
                        context.registerRollbackEvent(this, "epic creation", "uuid", uuid, "loc", loc);
                        String hdl = ps.requestHandle(uuid, loc);
                        logger.info("Created handle["+hdl+"] -> URI["+loc+"]");
                    } else {
                        logger.info("Update handle["+pid+"]["+uuid+"]["+cur+"] -> URI["+loc+"]");
                        context.registerRollbackEvent(this, "epic Update", "uuid", uuid, "loc", loc, "cur", cur);
                        ps.updateLocation(prefix+"/"+uuid, loc);
                        logger.info("Updated handle["+prefix+"/"+uuid+"] -> URI["+loc+"]");
                    }
                } else {
                    if (!col.hasPID())
                        logger.debug("Collection["+col+"] has no PID!");
                    if (!col.hasFID())
                        logger.debug("Collection["+col+"] has no FID!");
                }
            }

            for (Resource res:context.getSIP().getResources()) {
                if (res.isInsert() || res.isUpdate()) {
                    // TODO: update might be a PID update
                    if (res.hasPID() && res.hasFID()) {
                        String fid  = res.getFID().toString().replaceAll("#.*","");
                        String frag   = res.getFID().getRawFragment();
                        if (frag == null)
                            throw new DepositException("resource FID["+res.getFID()+"] isn't complete!");
                        String dsid = frag.replaceAll("@.*","");
                        String asof = frag.replaceAll(".*@","");

                        String pid    = res.getPID().toString().replaceAll("^http(s?)://hdl.handle.net/","hdl:");
                        String prefix = pid.replaceAll("hdl:([^/]*)/.*","$1");
                        String uuid   = pid.replaceAll(".*/","");
                        String loc  = server+"/objects/"+fid+"/datastreams/"+dsid+"/content?asOfDateTime="+asof;

                        logger.info("Create handle["+pid+"]["+uuid+"] -> URI["+loc+"]");
                        context.registerRollbackEvent(this, "epic creation", "uuid", uuid, "loc", loc);
                        String hdl  = ps.requestHandle(uuid, loc);
                        logger.info("Created handle["+hdl+"] -> URI["+loc+"]");
                    } else {
                        if (!res.hasPID())
                            logger.debug("Resource["+res+"] has no PID!");
                        if (!res.hasFID())
                            logger.debug("Resource["+res+"] has no FID!");
                    }
                }
            }
            
            Map<URI,URI> pids = context.getPIDs();
            for (URI pid:pids.keySet()) {
                URI red = pids.get(pid);
                if (red == null)
                    continue;
                if (red.toString().startsWith(namespace+":")) {
                    String fid    = red.toString().replaceAll("#.*","");
                    String frag   = red.getRawFragment();
                    if (frag == null) {
                        logger.warn("redirect FID["+red+"] isn't complete!");
                        continue;
                    }

                    String dsid   = frag.replaceAll("@.*","");
                    String asof   = frag.replaceAll(".*@","");
                    String loc    = server+"/objects/"+fid+"/datastreams/"+dsid+"/content?asOfDateTime="+asof;
                    red           = new URI(loc);
                }

                String pidStr = pid.toString().replaceAll("^http(s?)://hdl.handle.net/","hdl:");
                String prefix = pidStr.replaceAll("hdl:([^/]*)/.*","$1");
                String uuid   = pidStr.replaceAll(".*/","");

                logger.info("Lookup handle["+prefix+"/"+uuid+"]");
                String cur    = (isTest?null:ps.getPIDLocation(prefix+"/"+uuid));
                logger.info("Looked up handle["+prefix+"/"+uuid+"] -> URI["+cur+"]");
                    
                if (cur == null) {
                    logger.info("Create handle["+pid+"]["+uuid+"] -> URI["+red+"]");
                    context.registerRollbackEvent(this, "epic creation", "uuid", uuid, "loc", red.toString());
                    String hdl = ps.requestHandle(uuid, red.toString());
                    logger.info("Created handle["+hdl+"] -> URI["+red.toString()+"]");
                } else {
                    logger.info("Update handle["+pid+"]["+uuid+"]["+cur+"] -> URI["+red+"]");
                    context.registerRollbackEvent(this, "epic Update", "uuid", uuid, "loc", red.toString(), "cur", cur);
                    ps.updateLocation(prefix+"/"+uuid, red.toString());
                    logger.info("Updated handle["+prefix+"/"+uuid+"] -> URI["+red+"]");
                }
            }
        } catch(Exception e) {
            throw new DepositException(e);
        }
        
        return true;
    }
    public void rollback(Context context, List<XdmItem> events) {
    	if (events.size()>0) {
    	Boolean delMode = true;
    	String epic;
		try {
			epic = this.getParameter("epicConfig");
	    	File config = new File(epic);
	    	
	    	
	        if (!config.exists()) {
	            logger.error("The EPIC configuration["+epic+"] doesn't exist!");
	            return;
	        } else if (!config.isFile()) {
	            logger.error("The EPIC configuration["+epic+"] isn't a file!");
	            return;
	        } else if (!config.canRead()) {
	            logger.error("The EPIC configuration["+epic+"] can't be read!");
	            return;
	        }
	        
	        logger.debug("EPIC configuration["+config.getAbsolutePath()+"]");
	        
	        XMLConfiguration xConfig = new XMLConfiguration(config);
	        
	        boolean isTest = xConfig.getString("status") != null && xConfig.getString("status").equals("test");
            String tombstone = xConfig.getString("tombstone");
	        
	        PIDService ps = new PIDService(xConfig, null);
	        
	        for (ListIterator<XdmItem> iter = events.listIterator(events.size()); iter.hasPrevious();) {
	            XdmItem event = iter.previous();
	            try {
	                String tpe = Saxon.xpath2string(event, "@type");
	                if (tpe.equals("epic creation")) {
	                	String uuid = Saxon.xpath2string(event, "param[@name='uuid']/@value");
	                	String loc = Saxon.xpath2string(event, "param[@name='loc']/@value");
	                	
	                	if(delMode){
	                		try {
	                			
	                			ps.deleteHandle(uuid);
	                			logger.debug("rollback action[" + this.getName() + "] event[" + tpe + "] deleted handle [" + uuid + "]");
	                		}
	                		catch(IOException e) {
	                			delMode = false;
	                			logger.info("Rollback for deleting the handle[" + uuid + "] for event[" + tpe + "]not possible. Hence opting for tombstone");
	                		}
	                	}
	                	
	                	if(!delMode) {
	                		ps.updateLocation(uuid, tombstone);
	                		logger.debug("rollback action[" + this.getName() + "] event[" + tpe + "] updated handle [" + uuid + "]" + " to " + tombstone);
	                	}
	                }
	                else if (tpe.equals("epic Update")){
		                	String uuid = Saxon.xpath2string(event, "param[@name='uuid']/@value");
		                	String loc = Saxon.xpath2string(event, "param[@name='loc']/@value");
		                	String cur = Saxon.xpath2string(event, "param[@name='cur']/@value");
		                	
		                	ps.updateLocation(uuid, cur);
	                		logger.debug("rollback action[" + this.getName() + "] event[" + tpe + "] updated handle [" + uuid + "]" + " to " + cur);
	                }
	                else {
	                    logger.error("rollback action[" + this.getName() + "] rollback unknown event[" + tpe + "]!");
	                }
	            } catch (Exception ex) {
	                logger.error("rollback action[" + this.getName() + "] event[" + event + "] failed!", ex);
	            }
	        }
	        
		} catch (Exception e) {
			 logger.error("rollback action[" + this.getName() + " failed!", e);
		}


    	
        
    }
    }
}
