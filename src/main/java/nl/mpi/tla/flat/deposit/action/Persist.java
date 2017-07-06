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
import java.util.Set;

import javax.xml.transform.stream.StreamSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.sf.saxon.s9api.SaxonApiException;
import nl.mpi.tla.flat.deposit.Context;
import nl.mpi.tla.flat.deposit.DepositException;
import nl.mpi.tla.flat.deposit.sip.Resource;
import nl.mpi.tla.flat.deposit.sip.SIPInterface;
import nl.mpi.tla.flat.deposit.action.persist.util.PersistDatasetNameRetriever;
import nl.mpi.tla.flat.deposit.action.persist.util.PersistencePolicies;
import nl.mpi.tla.flat.deposit.action.persist.util.PersistencePolicy;
import nl.mpi.tla.flat.deposit.action.persist.util.PersistencePolicyLoader;
import nl.mpi.tla.flat.deposit.action.persist.util.PersistencePolicyMatcher;

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
                    Files.createDirectories(newResourceDir.toPath());
                    // add version number (if needed)
                    // 0 is used for the initial ingest (cf FC version numbers)
                    int v = 1;
                    while (newResourceFile.exists())
                        newResourceFile = new File(newResourceFile.toString()+"."+(v++));
                    // move the file to its persistent place
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
}
