/*
 * Copyright (C) 2015 menzowi
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
import java.nio.file.Path;
import java.util.Date;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.XdmNode;
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
public class CMDResource extends Resource {
    
    private static final org.slf4j.Logger logger = LoggerFactory.getLogger(CMDResource.class.getName());
    
    protected Node node = null;

    public CMDResource(URI base,Node node) throws DepositException {
        try {
            this.node = node;
            
            // ResourceRef value
            String str = Saxon.xpath2string(Saxon.wrapNode(node),"cmd:ResourceRef",null,NAMESPACES);
            if (str!=null && !str.trim().isEmpty()) {
                URI uri = (base!=null?base.resolve(new URI(null,null,str,null,null)):new URI(str));
                if (uri.toString().startsWith("lat:"))
                    this.setFID(uri);
                else if (uri.toString().matches("(http(s)?://hdl.handle.net/|hdl:)"))
                    this.setPID(uri);
                else
                    this.uri = uri;
            }
                
            // @lat:flatURI
            str = Saxon.xpath2string(Saxon.wrapNode(node),"cmd:ResourceRef/@lat:flatURI",null,NAMESPACES);
            if (str!=null && !str.trim().isEmpty()) {
                uri = (base!=null?base.resolve(new URI(null,null,str,null,null)):new URI(str));
                if (uri.toString().startsWith("lat:"))
                    this.setFID(uri);
                else if (uri.toString().matches("(http(s)?://hdl.handle.net/|hdl:)"))
                    this.setPID(uri);
                else if (this.uri==null)
                    this.uri = uri;
                else
                    throw new DepositException("two candidates for a resource URI["+this.uri+"]["+uri+"]!");
            }

            // @lat:localURI
            str = Saxon.xpath2string(Saxon.wrapNode(node),"cmd:ResourceRef/@lat:localURI",null,NAMESPACES);
            if (str!=null && !str.trim().isEmpty()) {
                uri = (base!=null?base.resolve(new URI(null,null,str,null,null)):new URI(str));
                if (uri.toString().startsWith("lat:"))
                    this.setFID(uri);
                else if (uri.toString().matches("(http(s)?://hdl.handle.net/|hdl:)"))
                    this.setPID(uri);
                else if (this.uri==null)
                    this.uri = uri;
                else
                    throw new DepositException("two candidates for a resource URI["+this.uri+"]["+uri+"]!");
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

            // MIME type
            if (Saxon.xpath2boolean(Saxon.wrapNode(node),"normalize-space(cmd:ResourceType/@mimetype)!=''",null,NAMESPACES)) {
                setMime(Saxon.xpath2string(Saxon.wrapNode(node),"cmd:ResourceType/@mimetype",null,NAMESPACES));
            }

            // determine file 
            if (this.uri!=null) {
                File resFile = new File(this.uri);
                if (resFile.exists()) {
                    if (resFile.canRead()) {
                        setFile(resFile);
                    } else
                        logger.warn("local file for ResourceProxy["+uri+"]["+resFile.getPath()+"] isn't readable!");
                } else
                        logger.warn("local file for ResourceProxy["+uri+"]["+resFile.getPath()+"] doesn't exist!");
            } else
                logger.warn("unknown local file for ResourceProxy["+uri+"] doesn't exist!");
        } catch (URISyntaxException|SaxonApiException ex) {
            throw new DepositException(ex);
        }
    }
    
    public Node getNode() {
        return this.node;
    }
    
    @Override
    public void save(SIPInterface sip) throws DepositException {
        if (node!=null) {
            try {
                if (hasMime()) {
                        Element rt = (Element)Saxon.unwrapNode((XdmNode)Saxon.xpath(Saxon.wrapNode(node), "cmd:ResourceType", null, NAMESPACES));
                        rt.setAttribute("mimetype", getMime());
                }
                if (hasFile()) {
                    Element rr = (Element)Saxon.unwrapNode((XdmNode)Saxon.xpath(Saxon.wrapNode(node), "cmd:ResourceRef", null, NAMESPACES));
                    rr.setAttribute("lat:localURI",sip.getBase().getParentFile().toPath().normalize().relativize(getFile().toPath().normalize()).toString());
                }
                if (hasPID()) {
                    Element rr = (Element)Saxon.unwrapNode((XdmNode)Saxon.xpath(Saxon.wrapNode(node), "cmd:ResourceRef", null, NAMESPACES));
                    rr.setTextContent(getPID().toString());
                }
                if (hasFID()) {
                    Element rr = (Element)Saxon.unwrapNode((XdmNode)Saxon.xpath(Saxon.wrapNode(node), "cmd:ResourceRef", null, NAMESPACES));
                    rr.setAttribute("lat:flatURI",getFID().toString());
                }
            } catch (SaxonApiException ex) {
                throw new DepositException(ex);
            }
        }
        clean();
    }
}
