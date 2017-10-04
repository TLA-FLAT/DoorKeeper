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
import java.net.URI;
import javax.xml.transform.Source;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.URIResolver;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.XdmAtomicValue;
import net.sf.saxon.s9api.XdmDestination;
import net.sf.saxon.s9api.XdmItem;
import net.sf.saxon.s9api.XdmNode;
import net.sf.saxon.s9api.XsltExecutable;
import net.sf.saxon.s9api.XsltTransformer;
import nl.mpi.tla.flat.deposit.Context;
import nl.mpi.tla.flat.deposit.DepositException;
import static nl.mpi.tla.flat.deposit.util.Global.NAMESPACES;
import nl.mpi.tla.flat.deposit.util.SaxonListener;
import nl.mpi.tla.flat.deposit.util.Saxon;
import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 *
 * @author menzowi
 */
public class FOXCreate extends AbstractAction {

    private static final Logger logger = LoggerFactory.getLogger(FOXCreate.class.getName());

    @Override
    public boolean perform(Context context) throws DepositException {
        try {
            
            // check for the user profile
            File owner = new File(getParameter("owner"));
            if (!owner.exists()) {
                logger.error("The owner profile doesn't exist!");
                return false;
            } else if (!owner.isFile()) {
                logger.error("The owner profile isn't a file!");
                return false;
            } else if (!owner.canRead()) {
                logger.error("The owner profile can't be read!");
                return false;
            }
            XMLConfiguration profile = new XMLConfiguration(owner);
            
            File fedora = new File(getParameter("fedoraConfig"));
            if (!fedora.exists()) {
                logger.error("The Fedora configuration["+fedora+"] doesn't exist!");
                return false;
            } else if (!fedora.isFile()) {
                logger.error("The Fedora configuration["+fedora+"] isn't a file!");
                return false;
            } else if (!fedora.canRead()) {
                logger.error("The Fedora configuration["+fedora+"] can't be read!");
                return false;
            }
            logger.debug("Fedora configuration["+fedora.getAbsolutePath()+"]");
            
            File dir = new File(getParameter("dir","./fox"));
            if (!dir.exists())
                 FileUtils.forceMkdir(dir);

            URIResolver org = Saxon.getXsltCompiler().getURIResolver();
            if (hasParameter("jar_cmd2fox")) {
                Saxon.getXsltCompiler().setURIResolver(new JarURIResolver(org,new File(getParameter("jar_cmd2fox"))));
            }
            
            File xsl = new File(getParameter("cmd2fox"));
            XsltExecutable cmd2fox = Saxon.buildTransformer(xsl);
            
            if (org!=null) {
                Saxon.getXsltCompiler().setURIResolver(org);
            }
            
            XsltTransformer fox = cmd2fox.load();
            SaxonListener listener = new SaxonListener("FOXCreate",MDC.get("sip"));
            fox.setMessageListener(listener);
            fox.setErrorListener(listener);
            
            // fixed parameters
            fox.setParameter(new QName("owner"), new XdmAtomicValue(profile.getString("name")));
            fox.setParameter(new QName("fox-base"), new XdmAtomicValue(dir.toString()));
            fox.setParameter(new QName("rels-doc"), Saxon.buildDocument(new StreamSource(FOXCreate.class.getResource("/FOXCreate/relations.xml").toString())));
            fox.setParameter(new QName("create-cmd-object"), new XdmAtomicValue(false));
            fox.setParameter(new QName("repository"), new XdmAtomicValue((new XMLConfiguration(fedora)).getString("publicServer")));
            
            // optional parameters
            if (hasParameter("management"))
                fox.setParameter(new QName("management-dir"), params.get("management"));
            if (hasParameter("policies"))
                fox.setParameter(new QName("policies-dir"), params.get("policies"));
            if (hasParameter("fits"))
                fox.setParameter(new QName("fits-dir"), params.get("fits"));
            if (hasParameter("icons"))
                fox.setParameter(new QName("icon-base"), params.get("icons"));
            if (hasParameter("collections-map"))
                fox.setParameter(new QName("collections-map"), Saxon.buildDocument(new StreamSource(getParameter("collections-map"))));
            if (hasParameter("oai-include-eval"))
                fox.setParameter(new QName("oai-include-eval"), params.get("oai-include-eval"));
            if (hasParameter("always-collection-eval"))
                fox.setParameter(new QName("always-collection-eval"), params.get("always-collection-eval"));
            if (hasParameter("always-compound-eval"))
                fox.setParameter(new QName("always-compound-eval"), params.get("always-compound-eval"));
            if (hasParameter("license-uri"))
                fox.setParameter(new QName("license-uri"), params.get("license-uri"));
            
            // additional parameters
            for (String param:this.params.keySet()) {
                if (param.startsWith("xsl-param-"))
                    fox.setParameter(new QName(param.replaceFirst("^xsl-param-","")), params.get(param));
            }
            
            // go
            fox.setSource(new DOMSource(context.getSIP().getRecord(),context.getSIP().getBase().toURI().toString()));
            XdmDestination destination = new XdmDestination();
            fox.setDestination(destination);
            fox.transform();
            
            String fid = Saxon.xpath2string(destination.getXdmNode(),"/*/@PID").replaceAll("#.*","").replaceAll("[^a-zA-Z0-9]", "_");
            File out = new File(dir + "/"+fid+"_CMD.xml");
            if (out.exists()) {
                // create a backup of the previous run
            }
            TransformerFactory.newInstance().newTransformer().transform(destination.getXdmNode().asSource(),new StreamResult(out));
            logger.info("created FOX["+out.getAbsolutePath()+"]");
            
            XdmNode cmd = Saxon.buildDocument(new StreamSource(new File(dir+"/"+fid+"_CMD.xml")));
            XdmItem self = Saxon.xpathSingle(cmd,"//cmd:CMD/cmd:Header/cmd:MdSelfLink",null,NAMESPACES);
            if (self!=null) {
                if (Saxon.xpath2boolean(self,"normalize-space(@lat:flatURI)!=''",null,NAMESPACES)) {
                    context.getSIP().setFID(new URI(Saxon.xpath2string(self,"@lat:flatURI",null,NAMESPACES)));
                }
            }
            for (XdmItem resource:Saxon.xpath(cmd,"//cmd:CMD/cmd:Resources/cmd:ResourceProxyList/cmd:ResourceProxy[cmd:ResourceType='Resource']",null,NAMESPACES)) {
                URI pid = new URI(Saxon.xpath2string(resource,"cmd:ResourceRef",null,NAMESPACES));
                if (Saxon.xpath2boolean(resource,"normalize-space(cmd:ResourceRef/@lat:flatURI)!=''",null,NAMESPACES)) {
                    context.getSIP().getResource(pid).setFID(new URI(Saxon.xpath2string(resource,"cmd:ResourceRef/@lat:flatURI",null,NAMESPACES)));
                }
            }
        } catch(Exception e) {
            throw new DepositException("The creation of FOX files failed!",e);
        }
        return true;
    }
    
    static class JarURIResolver implements URIResolver {
        
        private URIResolver resolver = null;
        private File xsl = null;
        
        public JarURIResolver(URIResolver resolver, File xsl) {
            this.resolver = resolver;
            this.xsl = xsl;
        }
        
        public Source resolve(String href,String base) throws TransformerException {
            if (href.equals("jar:cmd2fox.xsl")) {
                return new javax.xml.transform.stream.StreamSource(xsl);
            } else {
                return resolver.resolve(href,base);
            }
        }        
    }
}
