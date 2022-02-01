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

import nl.mpi.tla.flat.deposit.sip.*;
import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.XdmItem;
import net.sf.saxon.s9api.XdmNode;
import net.sf.saxon.s9api.XdmValue;
import nl.mpi.tla.flat.deposit.DepositException;
import static nl.mpi.tla.flat.deposit.util.Global.NAMESPACES;
import nl.mpi.tla.flat.deposit.util.Saxon;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 *
 * @author menzowi
 */
public class CMDResource extends Resource {
    
    private static final org.slf4j.Logger logger = LoggerFactory.getLogger(CMDResource.class.getName());
    
    protected Node node = null;
    
    protected String id = null;
    
    protected String namespace;
    protected XdmValue namespaces;

    public CMDResource(URI base,Node node,String namespace, XdmValue namespaces) throws DepositException {
        try {
            this.node = node;
            this.namespace = namespace;
            this.namespaces = namespaces;
            
            // id
            String str = Saxon.xpath2string(Saxon.wrapNode(node),"@id",null,NAMESPACES);
            if (str!=null && !str.trim().isEmpty())
                setID(str);

            // ResourceRef value
            str = Saxon.xpath2string(Saxon.wrapNode(node),"cmd:ResourceRef",null,NAMESPACES);
            if (str!=null && !str.trim().isEmpty()) {
                URI u = (base!=null?base.resolve(new URI(null,null,str,null,null)):new URI(str));
                boolean m = false;
                for(XdmItem ns:namespaces) {
                    if (u.toString().startsWith(ns.getStringValue()+":")) {
                        this.setFID(u);
                        m = true;
                    }
                }
                if (!m) {
                    if (u.toString().matches("(http(s)?://hdl.handle.net/|hdl:).*"))
                        this.setPID(u);
                    else
                        this.uri = u;
                }
            }
                
            // @lat:flatURI
            str = Saxon.xpath2string(Saxon.wrapNode(node),"cmd:ResourceRef/@lat:flatURI",null,NAMESPACES);
            if (str!=null && !str.trim().isEmpty()) {
                URI u = (base!=null?base.resolve(str):new URI(str));
                boolean m = false;
                for(XdmItem ns:namespaces) {
                    if (u.toString().startsWith(ns.getStringValue()+":")) {
                        this.setFID(u);
                        m = true;
                    }
                }
                if (!m) {
                    if (u.toString().matches("(http(s)?://hdl.handle.net/|hdl:).*"))
                        this.setPID(u);
                    else if (this.uri==null)
                        this.uri = u;
                    else if (!this.uri.equals(u))
                        throw new DepositException("two candidates for a resource URI["+this.uri+"]["+u+"]!");
                }
            }

            // @lat:localURI
            str = Saxon.xpath2string(Saxon.wrapNode(node),"cmd:ResourceRef/@lat:localURI",null,NAMESPACES);
            if (str!=null && !str.trim().isEmpty()) {
                URI u = (base!=null?base.resolve(new URI(null,null,str,null,null)):new URI(str));
                boolean m = false;
                for(XdmItem ns:namespaces) {
                    if (u.toString().startsWith(ns.getStringValue()+":")) {
                        this.setFID(u);
                        m = true;
                    }
                }
                if (!m) {
                    if (u.toString().matches("(http(s)?://hdl.handle.net/|hdl:).*"))
                        this.setPID(u);
                    else if (this.uri==null)
                        this.uri = u;
                    else if (!this.uri.equals(u))
                        throw new DepositException("two candidates for a resource URI["+this.uri+"]["+u+"]!");
                }
            }
            
            // make sure we have at least an URI
            if (this.uri == null) {
                if (hasPID())
                    this.uri = this.getPID();
                else if (hasFID()) {
                    this.uri = this.getFID();
                    logger.debug("SMELL: Resource URI set to FID["+this.uri+"]");
                } else
                    throw new DepositException("no resource URI found!");
            }

            // @lat:status
            str = Saxon.xpath2string(Saxon.wrapNode(node),"cmd:ResourceRef/@lat:status",null,NAMESPACES);
            if (str!=null && !str.trim().isEmpty()) {
                //NOOP, INSERT, UPDATE, DELETE
                if (str.trim().equalsIgnoreCase("NOOP")) {
                        this.setStatus(Status.NOOP);
                } else if (str.trim().equalsIgnoreCase("INSERT")) {
                    this.setStatus(Status.INSERT);
                } else if (str.trim().equalsIgnoreCase("UPDATE")) {
                    this.setStatus(Status.UPDATE);
                } else if (str.trim().equalsIgnoreCase("DELETE")) {
                    this.setStatus(Status.DELETE);
                } else
                    throw new DepositException("unknown status["+str+"]!");
            }

            // MIME type
            if (Saxon.xpath2boolean(Saxon.wrapNode(node),"normalize-space(cmd:ResourceType/@mimetype)!=''",null,NAMESPACES)) {
                setMime(Saxon.xpath2string(Saxon.wrapNode(node),"cmd:ResourceType/@mimetype",null,NAMESPACES));
            }

            // determine file 
            if (this.uri!=null && this.uri.toString().startsWith("file:")) {
                File resFile = new File(this.uri);
                if (resFile.exists()) {
                    if (resFile.canRead()) {
                        setFile(resFile);
                    } else
                        logger.error("local file for ResourceProxy["+this.uri+"]["+resFile.getPath()+"] isn't readable!");
                } else
                    logger.error("local file for ResourceProxy["+this.uri+"]["+resFile.getPath()+"] doesn't exist!");
            } else
                logger.debug("no local file for ResourceProxy["+this.uri+"] known!");
        } catch (URISyntaxException|SaxonApiException ex) {
            throw new DepositException(ex);
        }
    }
    
    public Node getNode() {
        return this.node;
    }
    
    // id
    public boolean hasID() {
        return (this.id != null);
    }
    
    public void setID(String id) throws DepositException {
        if (this.id!=null)
            throw new DepositException("Resource["+this.uri+"] has already an ID!");
        this.id = id;
        dirty();
    }
    
    public String getID() throws DepositException {
        if (this.id==null)
            throw new DepositException("Resource["+this.uri+"] has no ID yet!");
        return this.id;
    }
    
    @Override
    public void setFID(URI fid) throws DepositException {
        boolean m = false;
        for(XdmItem ns:namespaces) {
            if (fid.toString().startsWith(ns+":")) {
                super.setFID(fid);
                m = true;
            }
        }
        if (!m)
            throw new DepositException("The Resource["+fid+"] isn't a valid FLAT Fedora Commons PID!");
    }

    @Override
    public void save(SIPInterface sip) throws DepositException {
        if (node!=null) {
            try {
                if (hasMime()) {
                        Element rt = (Element)Saxon.unwrapNode((XdmNode)Saxon.xpath(Saxon.wrapNode(node), "cmd:ResourceType", null, NAMESPACES));
                        rt.setAttribute("mimetype", getMime());
                }
                Element rr = (Element)Saxon.unwrapNode((XdmNode)Saxon.xpath(Saxon.wrapNode(node), "cmd:ResourceRef", null, NAMESPACES));
                if (hasFile())
                    rr.setAttribute("lat:localURI",sip.getBase().getParentFile().toPath().normalize().relativize(getFile().toPath().normalize()).toString());
                if (hasPID())
                    rr.setTextContent(getPID().toString());
                if (hasFID())
                    rr.setAttribute("lat:flatURI",getFID().toString());
                if (this.isNoop())
                    rr.setAttribute("lat:status","NOOP");
                else if (this.isInsert())
                    rr.setAttribute("lat:status","INSERT");
                else if (this.isUpdate())
                    rr.setAttribute("lat:status","UPDATE");
                else if (this.isDelete())
                    rr.setAttribute("lat:status","DELETE");
            } catch (SaxonApiException ex) {
                throw new DepositException(ex);
            }
        }
        clean();
    }
}
