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

import java.io.File;

import org.apache.commons.lang.builder.EqualsBuilder;
import org.apache.commons.lang.builder.HashCodeBuilder;

/**
 * Class representing a single persistence policy.
 * @author guisil
 */
public class PersistencePolicy {

	private final String property;
	private final String regex;
	private final File target;
	
	
	public PersistencePolicy(String property, String regex, File target) {
		this.property = property;
		this.regex = regex;
		this.target = target;
	}


	public String getProperty() {
		return property;
	}

	public String getRegex() {
		return regex;
	}

	public File getTarget() {
		return target;
	}
	
	
	@Override
	public int hashCode() {
		
		HashCodeBuilder hashCodeB = new HashCodeBuilder()
				.append(property)
				.append(regex)
				.append(target);
		
		return hashCodeB.toHashCode();
	}
	
	@Override
	public boolean equals(Object obj) {
		
		if(this == obj) {
			return true;
		}
		
		if(!(obj instanceof PersistencePolicy)) {
			return false;
		}
		
		PersistencePolicy other = (PersistencePolicy) obj;
		
		EqualsBuilder equalsB = new EqualsBuilder()
				.append(property, other.getProperty())
				.append(regex, other.getRegex())
				.append(target, other.getTarget());
		
		return equalsB.isEquals();
	}
	
	@Override
	public String toString() {
		return "property = " + property + "; regex = " + regex + "; target = " + target;
	}
}
