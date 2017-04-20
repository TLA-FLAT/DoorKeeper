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
package nl.mpi.tla.flat.deposit.sip.cmdi;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import javax.xml.transform.dom.DOMSource;
import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.XdmItem;
import net.sf.saxon.s9api.XdmNode;
import nl.mpi.tla.flat.deposit.DepositException;
import nl.mpi.tla.flat.deposit.sip.Collection;
import nl.mpi.tla.flat.deposit.sip.Resource;
import nl.mpi.tla.flat.deposit.sip.SIPInterface;
import nl.mpi.tla.flat.deposit.util.Global;
import static nl.mpi.tla.flat.deposit.util.Global.NAMESPACES;
import nl.mpi.tla.flat.deposit.util.Saxon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 *
 * @author menzowi
 */
public class CMD implements SIPInterface {
    
    private static final Logger logger = LoggerFactory.getLogger(CMD.class.getName());
    protected Marker marker = null;
    
    public static String CMD_NS = "http://www.clarin.eu/cmd/";
    public static String LAT_NS = "http://lat.mpi.nl/";
    
    protected Node self = null;
    protected File base = null;
    protected URI pid = null;
    protected URI fid = null;

    protected Document rec = null;
    
    protected Set<Resource> resources = new LinkedHashSet();
    protected Set<Collection> collections = new LinkedHashSet();
    
    protected Map<String,String> namespaces = new LinkedHashMap<>();
    
    protected boolean dirty = false;
    
    public CMD(File spec) throws DepositException {
        this.base = spec;
        load(spec);
        loadResources();
        loadCollections();
    }
    
    public File getBase() {
        return this.base;
    }
    
    public Document getRecord() {
        return this.rec;
    }
    
    // PID
    public boolean hasPID() {
        return (this.pid != null);
    }
    
    public void setPID(URI pid) throws DepositException {
        if (this.pid!=null)
            throw new DepositException("SIP["+this.base+"] has already a PID!");
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
            throw new DepositException("SIP["+this.base+"] has no PID yet!");
        return this.pid;
    }
       
    // FID
    public boolean hasFID() {
        return (this.fid != null);
    }
    
    public void setFID(URI fid) throws DepositException {
        if (this.fid!=null)
            throw new DepositException("SIP["+this.base+"] has already a Fedora Commons PID!");
        if (fid.toString().startsWith("lat:")) {
            this.fid = fid;
        } else {
            throw new DepositException("The URI["+fid+"] isn't a valid FLAT Fedora Commons PID!");
        }
        dirty();
    }
    
    public void setFIDStream(String dsid) throws DepositException {
        if (this.fid==null)
            throw new DepositException("SIP["+this.base+"] has no Fedora Commons PID yet!");
        try {
            this.fid = new URI(this.fid.toString()+"#"+dsid);
        } catch (URISyntaxException ex) {
           throw new DepositException(ex);
        }
        dirty();
    }
    
    public void setFIDasOfTimeDate(Date date) throws DepositException {
        if (this.fid==null)
            throw new DepositException("SIP["+this.base+"] has no Fedora Commons PID yet!");
        try {
            this.fid = new URI(this.fid.toString()+"@"+Global.asOfDateTime(date));
        } catch (URISyntaxException ex) {
           throw new DepositException(ex);
        }
        dirty();
    }
    
    public URI getFID() throws DepositException {
        if (this.fid==null)
            throw new DepositException("SIP["+this.base+"] has no Fedora Commons PID yet!");
        return this.fid;
    }
       
    // resources
    
    private void loadResources() throws DepositException {
        try {
            for (XdmItem resource:Saxon.xpath(Saxon.wrapNode(this.rec),"/cmd:CMD/cmd:Resources/cmd:ResourceProxyList/cmd:ResourceProxy[cmd:ResourceType='Resource']",null,NAMESPACES)) {
                Node resNode = Saxon.unwrapNode((XdmNode)resource);
                Resource res = new CMDResource(base.toURI(),resNode);
                if (resources.contains(res)) {
                    logger.warn("double ResourceProxy["+Saxon.xpath2string(resource,"cmd:ResourceRef",null,NAMESPACES)+"]["+res.getURI()+"]["+(res.hasFID()?res.getFID():"")+"]!");
                } else {
                    resources.add(res);
                    logger.debug("ResourceProxy["+Saxon.xpath2string(resource,"cmd:ResourceRef",null,NAMESPACES)+"]["+res.getURI()+"]["+(res.hasFID()?res.getFID():"")+"]");
                }
            }
        } catch(Exception e) {
            throw new DepositException(e);
        }
    }
    
    public Set<Resource> getResources() {
        return this.resources;
    }
    
    public Resource getResource(URI pid) throws DepositException {
        for (Resource res:getResources()) {
            if (pid.toString().matches("^(hdl:|http(s)?://hdl.handle.net/).*")) {
                if (res.getPID().toString().replaceAll("^(hdl:|http(s)?://hdl.handle.net/)","").equals(pid.toString().replaceAll("^(hdl:|http(s)?://hdl.handle.net/)","")))
                    return res;
            } else if (res.getPID().equals(pid)) {
                return res;
            }
        }
        throw new DepositException("SIP["+this.base+"] has no Resource with this PID["+pid+"]!");
    }
    
    public void saveResources() throws DepositException {
        for (Resource res:getResources())
            res.save(this);
    }
    
    // Collections
    
    private void loadCollections() throws DepositException {
        try {
            for (XdmItem collection:Saxon.xpath(Saxon.wrapNode(this.rec),"/cmd:CMD/cmd:Resources/cmd:IsPartOfList/cmd:IsPartOf",null,NAMESPACES)) {
                Node colNode = Saxon.unwrapNode((XdmNode)collection);
                Collection col = new CMDCollection(base.toURI(), colNode);
                if (collections.contains(col)) {
                    logger.warn("double IsPartOf["+collection.getStringValue()+"]["+col.getURI()+"]["+(col.hasFID()?col.getFID():"")+"]!");
                } else {
                    collections.add(col);
                    logger.debug("IsPartOf["+collection.getStringValue()+"]["+col.getURI()+"]["+(col.hasFID()?col.getFID():"")+"]");
                }
            }
        } catch(SaxonApiException e) {
            throw new DepositException(e);
        }
    }

    public Set<Collection> getCollections() {
        return this.collections;
    }
    
    public Collection getCollection(URI pid) throws DepositException {
        for (Collection col:getCollections()) {
            if (pid.toString().matches("^(hdl:|http(s)?://hdl.handle.net/).*")) {
                if (col.getPID().toString().replaceAll("^(hdl:|http(s)?://hdl.handle.net/)","").equals(pid.toString().replaceAll("^(hdl:|http(s)?://hdl.handle.net/)","")))
                    return col;
            } else if (col.getPID().equals(pid)) {
                return col;
            }
        }
        throw new DepositException("SIP["+this.base+"] has no Resource with this PID["+pid+"]!");
    }
       
    public void saveCollections() throws DepositException {
        for (Collection col:getCollections())
            col.save(this);
    }
    
    // Dirty or clean?
    
    protected void dirty() {
        this.dirty = true;
    }
    
    public boolean isDirty() {
        if (!this.dirty) {
            // dirty resources?
            for (Resource res:getResources()) {
                if (res.isDirty()) {
                    this.dirty = true;
                    break;
                }
            }
        }
        if (!this.dirty) {
            // dirty collections?
            for (Collection col:getCollections()) {
                if (col.isDirty()) {
                    this.dirty = true;
                    break;
                }
            }
        }
        return this.dirty;
    }
    
    protected void clean() {
        this.dirty = false;
    }

    // IO
    
    @Override
    public void load(File spec) throws DepositException {
        try {
            this.rec = Saxon.buildDOM(spec);

            Element cmd = (Element)Saxon.unwrapNode((XdmNode)Saxon.xpath(Saxon.wrapNode(this.rec), "/cmd:CMD", null, NAMESPACES));
            cmd.setAttributeNS("http://www.w3.org/2000/xmlns/", "xmlns:lat", LAT_NS);
            
            String self = Saxon.xpath2string(Saxon.wrapNode(this.rec), "/cmd:CMD/cmd:Header/cmd:MdSelfLink", null, NAMESPACES);
            if (!self.trim().equals("")) {
                try {
                    this.setPID(new URI(self));
                } catch(DepositException e) {
                    logger.warn("current MdSelfLink["+self+"] isn't a valid PID, ignored for now!");
                }
            }
            String flat = Saxon.xpath2string(Saxon.wrapNode(this.rec), "/cmd:CMD/cmd:Header/cmd:MdSelfLink/@lat:flatURI", null, NAMESPACES);
            if (!flat.trim().equals("")) {
                try {
                    this.setFID(new URI(flat));
                } catch(DepositException e) {
                    logger.warn("current MdSelfLink/@lat:flatURI["+flat+"] isn't a valid Fecora Commons PID, ignored for now!");
                }
            }
        } catch(Exception e) {
            throw new DepositException(e);
        }
    }
    
    @Override
    public boolean save() throws DepositException {
        return save(false);
    }

    public boolean save(boolean force) throws DepositException {
        boolean save = force || isDirty();
        if (save) {
            try {
                if (base.exists()) {
                    // always keep the org around
                    File org = new File(base.toString()+".org");
                    if (!org.exists())
                        Files.copy(base.toPath(),org.toPath());
                    // and keep timestamped backups
                    FileTime stamp = Files.getLastModifiedTime(base.toPath());
                    SimpleDateFormat df = new SimpleDateFormat("yyyyMMdd-HHmmss");
                    String ext = df.format(stamp.toMillis());
                    int i = 0;
                    File bak = new File(base.toString()+"."+ext);
                    while (bak.exists())
                        bak = new File(base.toString()+"."+ext+"."+(++i));
                    Files.move(base.toPath(),bak.toPath());
                    logger.debug("saved backup to ["+bak+"]");
                }
                // put PID into place
                if (this.hasPID()) {
                    Element self = null;
                    XdmItem _self = Saxon.xpathSingle(Saxon.wrapNode(this.rec),"/cmd:CMD/cmd:Header/cmd:MdSelfLink",null,NAMESPACES);
                    if (_self==null) {
                        Element profile = (Element)Saxon.unwrapNode((XdmNode)Saxon.xpath(Saxon.wrapNode(this.rec), "/cmd:CMD/cmd:Header/cmd:MdProfile", null, NAMESPACES));
                        Element header = (Element)Saxon.unwrapNode((XdmNode)Saxon.xpath(Saxon.wrapNode(this.rec), "/cmd:CMD/cmd:Header", null, NAMESPACES));
                        self = rec.createElementNS(CMD_NS, "MdSelfLink");
                        self.setTextContent(this.getPID().toString());
                        header.insertBefore(self, profile);
                    } else {
                        self = (Element)Saxon.unwrapNode((XdmNode)_self);
                    }
                    if (this.hasFID()) {
                        self.setAttribute("lat:flatURI",this.getFID().toString());
                    }
                }   
                // save changes to the resource list
                saveResources();
                // save changes to the collections list
                saveCollections();
                DOMSource source = new DOMSource(rec);
                Saxon.save(source,base);
                logger.debug("saved new version to ["+base+"]");
            } catch(Exception e) {
                throw new DepositException(e);
            }
            clean();
        }
        return save;
    }
}
