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
package nl.mpi.tla.flat.deposit.sip;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import nl.mpi.tla.flat.deposit.DepositException;
import nl.mpi.tla.flat.deposit.util.Global;
import static nl.mpi.tla.flat.deposit.util.Global.NAMESPACES;
import nl.mpi.tla.flat.deposit.util.Saxon;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 *
 * @author menzowi
 */
abstract public class Collection {
    
    private static final org.slf4j.Logger logger = LoggerFactory.getLogger(Collection.class.getName());
    
    protected URI uri = null;
    protected URI pid = null;
    protected URI fid = null;
    protected Set<Collection> collections = new LinkedHashSet();
    protected boolean dirty = false;
    
    public URI getURI() {
        return this.uri;
    }
    
    // PID
    public boolean hasPID() {
        return (this.pid != null);
    }
    
    public void setPID(URI pid) throws DepositException {
        if (this.pid!=null)
            logger.warn("Collection["+this.uri+"] has already a PID["+this.pid+"]! new PID["+pid+"]");
        if (pid.toString().startsWith("hdl:")) {
            this.pid = pid;
        } else if (pid.toString().matches("http(s)?://hdl.handle.net/.*")) {
            try {
                this.pid = new URI(pid.toString().replace("http(s)?://hdl.handle.net/", "hdl:"));
            } catch (URISyntaxException ex) {
                throw new DepositException(ex);
            }
        } else {
            throw new DepositException("The URI["+pid+"] isn't a valid PID!");
        }
        dirty();
    }
    
    public URI getPID() throws DepositException {
        if (this.pid==null)
            throw new DepositException("Resource["+this.uri+"] has no PID yet!");
        return this.pid;
    }
    
    // FID
    public boolean hasFID() {
        return (this.fid != null);
    }
    
    public void setFID(URI fid) throws DepositException {
        if (this.fid!=null)
            logger.warn("Collection["+this.uri+"] has already a Fedora Commons PID["+this.fid+"]! new Fedora Commons PID["+fid+"]");
        if (fid.toString().startsWith("lat:")) {
            this.fid = fid;
        } else {
            throw new DepositException("The Collection["+fid+"] isn't a valid FLAT Fedora Commons PID!");
        }
        dirty();
    }
    
    public void setFIDStream(String dsid) throws DepositException {
        if (this.fid==null)
            throw new DepositException("Collection["+this.uri+"] has no Fedora Commons PID yet!");
        try {
            this.fid = new URI(this.fid.toString()+"#"+dsid);
        } catch (URISyntaxException ex) {
           throw new DepositException(ex);
        }
    }
    
    public void setFIDasOfTimeDate(Date date) throws DepositException {
        if (this.fid==null)
            throw new DepositException("Collection["+this.uri+"] has no Fedora Commons PID yet!");
        try {
            this.fid = new URI(this.fid.toString()+"@"+Global.asOfDateTime(date));
        } catch (URISyntaxException ex) {
           throw new DepositException(ex);
        }
        dirty();
    }
    
    public URI getFID() throws DepositException {
        if (this.fid==null)
            throw new DepositException("Collection["+this.uri+"] has no Fedora Commons PID yet!");
        return this.fid;
    }
    
    // parent collections
    
    public boolean hasParentCollections() {
        return !this.collections.isEmpty();
    }
    
    public void addParentCollection(Collection col) throws DepositException {
        this.collections.add(col);
        dirty();
    }
    
    public Set<Collection> getParentCollections(boolean deep) {
        Set<Collection> colls =  new LinkedHashSet();
        colls.addAll(this.collections);
        if (deep) {
            for (Collection col:this.collections) {
                colls.addAll(col.getParentCollections(deep));
            }
        }
        return colls;
    }
           
    public Set<Collection> getParentCollections() {
        return getParentCollections(false);
    }
           
    // dirty or not 
    
    protected void dirty() {
        this.dirty = true;
    }
    
    public boolean isDirty() {
        return this.dirty;
    }
    
    protected void clean() {
        this.dirty = false;
    }
    
    // save
    
    abstract public void save(SIPInterface sip) throws DepositException;
    
    // compare
       
    @Override
    public boolean equals(Object other) {
        if (other == null)
            return false;
        if (other == this)
            return true;
        if (!(other instanceof Collection))
            return false;
        Collection otherCollection = (Collection)other;
        return otherCollection.uri.equals(this.uri);
    }
    
    @Override
    public int hashCode() {
        return this.uri.hashCode();
    }   
}