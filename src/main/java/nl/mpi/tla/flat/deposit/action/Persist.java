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
import java.nio.file.Files;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;
import java.util.logging.Level;

import javax.xml.transform.stream.StreamSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.XdmItem;
import nl.mpi.tla.flat.deposit.Context;
import nl.mpi.tla.flat.deposit.DepositException;
import nl.mpi.tla.flat.deposit.Flow;
import nl.mpi.tla.flat.deposit.sip.Resource;
import nl.mpi.tla.flat.deposit.sip.SIPInterface;
import nl.mpi.tla.flat.deposit.action.persist.util.PersistDatasetNameRetriever;
import nl.mpi.tla.flat.deposit.action.persist.util.PersistencePolicies;
import nl.mpi.tla.flat.deposit.action.persist.util.PersistencePolicy;
import nl.mpi.tla.flat.deposit.action.persist.util.PersistencePolicyLoader;
import nl.mpi.tla.flat.deposit.action.persist.util.PersistencePolicyMatcher;
import nl.mpi.tla.flat.deposit.util.Saxon;

/**
 *
 * @author menzowi
 * @author guisil
 */
public class Persist extends AbstractAction {
	
    private static final Logger logger = LoggerFactory.getLogger(Persist.class);
    
    @Override
    public boolean perform(Context context) throws DepositException {
        
    	String resourcesDir = getParameter("resourcesDir", null);
    	String policyFile = getParameter("policyFile", null);
    	
    	PersistencePolicyLoader policyLoader = newPersistencePolicyLoader(new File(resourcesDir));
    	
    	SIPInterface sip = context.getSIP();
    	Set<Resource> sipResources = sip.getResources();
    	
    	PersistDatasetNameRetriever datasetNameRetrieved = newPersistDatasetNameRetriever();
    	logger.debug("xpath_dataset_name: " + getParameter("xpathDatasetName"));
    	String datasetName = datasetNameRetrieved.getDatasetName(sip.getRecord(), getParameter("xpathDatasetName"));
    	
    	PersistencePolicies policies;
        try {
            policies = policyLoader.loadPersistencePolicies(new StreamSource(policyFile), sip, datasetName);
        } catch (SaxonApiException | IllegalStateException ex) {
            String message = "Error loading policy file '" + policyFile.toString() + "'";
            logger.error(message, ex);
            throw new DepositException(message, ex);
        }
		
    	for(Resource res : sipResources) {
            if (res.getStatus()==Resource.Status.INSERT || res.getStatus()==Resource.Status.UPDATE) {
                PersistencePolicyMatcher policyMatcher = newPersistencePolicyMatcher(policies);
                PersistencePolicy matchedPolicy = policyMatcher.matchPersistencePolicy(res);
                logger.debug("Matched policy for resource '" + res.getFile().getName() + "': " + matchedPolicy);
                File newResourceDir = matchedPolicy.getTarget();
                File newResourceFile = new File(newResourceDir, res.getFile().getName());
                try {
                    context.registerRollbackEvent(this, "mkdir", "dir", newResourceDir.toPath().toString());
                    Files.createDirectories(newResourceDir.toPath());
                    // add version number (if needed)
                    // 0 is used for the initial ingest (cf FC version numbers)
                    int v = 1;
                    while (newResourceFile.exists())
                        newResourceFile = new File(newResourceFile.toString()+"."+(v++));
                    // move the file to its persistent place
                    context.registerRollbackEvent(this, "mv", "src", res.getFile().toPath().toString(),"dst",newResourceFile.toPath().toString());
                    Files.move(res.getFile().toPath(), newResourceFile.toPath());
                } catch (IOException ex) {
                    String message = "Error moving resource from " + res.getFile() + " to " + newResourceFile; 
                    logger.error(message, ex);
                    throw new DepositException(message, ex);
                }
                logger.info("Moved resource from " + res.getFile() + " to " + newResourceFile);
                res.setFile(newResourceFile);
            }
    	}
    	
    	return true;
    }
    
    PersistencePolicyLoader newPersistencePolicyLoader(File resourcesBaseDir) {
    	return new PersistencePolicyLoader(resourcesBaseDir);
    }
    
    PersistencePolicyMatcher newPersistencePolicyMatcher(PersistencePolicies policies) {
    	return new PersistencePolicyMatcher(policies);
    }
    
    PersistDatasetNameRetriever newPersistDatasetNameRetriever() {
    	return new PersistDatasetNameRetriever();
    }
    
    public void rollback(Context context,List<XdmItem> events) {
        for (ListIterator<XdmItem> iter = events.listIterator(events.size());iter.hasPrevious();) {
            XdmItem event = iter.previous();
            try {
                String tpe = Saxon.xpath2string(event, "@type");
                if (tpe.equals("mv")) {
                    File src = new File(Saxon.xpath2string(event, "param[@name='src']/@value"));
                    File dst = new File(Saxon.xpath2string(event, "param[@name='dst']/@value"));
                    if (dst.exists() && dst.isFile() && dst.canWrite() && !src.exists()) {
                        Files.move(dst.toPath(), src.toPath());
                        logger.debug("rollback action["+this.getName()+"] event["+tpe+"] moved ["+dst+"] back to ["+src+"]");
                    } else
                        logger.error("rollback action["+this.getName()+"] event["+Saxon.xpath2string(event, "@type")+"] failed source["+src+"] and destination["+dst+"] can't be restored!");
                } else if (tpe.equals("mkdir")) {
                    File dir = new File(Saxon.xpath2string(event, "param[@name='dir']/@value"));
                    Files.deleteIfExists(dir.toPath());
                    logger.debug("rollback action["+this.getName()+"] event["+tpe+"] removed dir["+dir+"]");
                } else
                    logger.error("rollback action["+this.getName()+"] rollback unknown event["+tpe+"]!");
            } catch (Exception ex) {
                logger.error("rollback action["+this.getName()+"] event["+event+"] failed!",ex);
            }
        }
    }
    
}
