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
package nl.mpi.tla.flat.deposit.action.persist.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import nl.mpi.tla.flat.deposit.DepositException;
import nl.mpi.tla.flat.deposit.sip.Resource;

/**
 * Class used to match resources with persistence policies.
 * @author guisil
 */
public class PersistencePolicyMatcher {

	private static final Logger logger = LoggerFactory.getLogger(PersistencePolicyMatcher.class);
	
	private PersistencePolicies policies;
	
	
	public PersistencePolicyMatcher(PersistencePolicies policies) {
		this.policies = policies;
	}
	
	
	/**
	 * Retrieves a persistence policy that matches the given resource.
	 * @param resource Resource to match
	 * @return Appropriate policy
	 * @throws DepositException
	 */
	public PersistencePolicy matchPersistencePolicy(Resource resource) throws DepositException {
		
		logger.debug("Trying to find policy for resource '{}'", resource.getFile().getName());
		for(PersistencePolicy policy : policies.getAllPolicies()) {
			if("mimetype".equals(policy.getProperty())) {
				Pattern p = Pattern.compile(policy.getRegex());
				Matcher m = p.matcher(resource.getMime());
				if(m.matches()) {
					logger.debug("Found matching policy for mimetype '{}'", resource.getMime());
					return policy;
				}
			} else {
				throw new UnsupportedOperationException("Only mimetype matching supported at the moment for the persistence policy");
			}
		}
		logger.debug("A matching policy was not found; using default policy");
		return policies.getDefaultPolicy();
	}
}
