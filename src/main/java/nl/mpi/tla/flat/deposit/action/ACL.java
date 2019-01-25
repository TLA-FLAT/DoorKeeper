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
import java.io.FileOutputStream;
import java.io.OutputStream;
import javax.xml.transform.Source;
import javax.xml.transform.TransformerException;
import javax.xml.transform.URIResolver;
import javax.xml.transform.stream.StreamSource;
import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.XdmAtomicValue;
import net.sf.saxon.s9api.XdmDestination;
import net.sf.saxon.s9api.XsltTransformer;
import nl.mpi.tla.flat.deposit.Context;
import nl.mpi.tla.flat.deposit.DepositException;
import nl.mpi.tla.flat.deposit.util.Saxon;
import nl.mpi.tla.flat.deposit.util.SaxonListener;
import org.apache.commons.io.FileUtils;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 *
 * @author menzowi
 */
public class ACL extends AbstractAction {

    private static final Logger logger = LoggerFactory.getLogger(ACL.class.getName());

    @Override
    public boolean perform(Context context) throws DepositException {
        URIResolver org = Saxon.getXsltCompiler().getURIResolver();
        try {
            String namespace = this.getParameter("fedoraNamespace", context.getProperty("fedoraNamespace", "lat").toString());
            // check for the policy
            File policy = new File(getParameter("policy", "./metadata/policy.n3"));
            if (!policy.exists()) {
                logger.info("No access policy specified, the default policy will be used.");
                return true;
            } else if (!policy.isFile()) {
                logger.error("The access policy isn't a file!");
                return false;
            } else if (!policy.canRead()) {
                logger.error("The access policy can't be read!");
                return false;
            }
            // check for the roles
            File roles = null;
            if (this.hasParameter("roles")) {
                roles = new File(getParameter("roles"));
                if (!roles.exists()) {
                    logger.info("The roles policy doesn't exist!");
                    return true;
                } else if (!roles.isFile()) {
                    logger.error("The roles policy isn't a file!");
                    return false;
                } else if (!roles.canRead()) {
                    logger.error("The roles policy can't be read!");
                    return false;
                }
            }
            // create the dir
            File dir = new File(getParameter("dir", "./acl"));
            if (!dir.exists()) {
                FileUtils.forceMkdir(dir);
            }

            if (hasParameter("jar_acl2xacml")) {
                Saxon.getXsltCompiler().setURIResolver(new ACL.JarURIResolver(org,new File(getParameter("jar_acl2xacml"))));
            } else {
                Saxon.getXsltCompiler().setURIResolver(new ACL.JarURIResolver(org));
            }
            
            // convert policy N3 to TriX
            // https://jena.apache.org/documentation/io/
            Model model = ModelFactory.createDefaultModel() ;
            model.read(policy.getAbsolutePath()) ;
            OutputStream trix = new FileOutputStream(new File(dir +"/policy.trix"));
            RDFDataMgr.write(trix, model, Lang.TRIX);

            // convert trix to semantic triples using ACL/sl-trix-to-sem-triples.xsl
            XsltTransformer trix2sem = Saxon.buildTransformer(ACL.class.getResource("/ACL/sl-trix-to-sem-triples.xsl")).load();
            SaxonListener listener = new SaxonListener("ACL",MDC.get("sip"));
            trix2sem.setMessageListener(listener);
            trix2sem.setErrorListener(listener);
            trix2sem.setSource(new StreamSource(dir +"/policy.trix"));
            XdmDestination destination = new XdmDestination();
            trix2sem.setDestination(destination);
            trix2sem.transform();
            Saxon.save(destination, new File(dir + "/policy.sem"));
            
            // convert sem triples to an intermediate ACL using ACL/WebACL2ACL.xsl
            XsltTransformer wacl2acl = Saxon.buildTransformer(ACL.class.getResource("/ACL/WebACL2ACL.xsl")).load();
            wacl2acl.setMessageListener(listener);
            wacl2acl.setErrorListener(listener);
            wacl2acl.setParameter(new QName("ns"), new XdmAtomicValue(namespace));
            wacl2acl.setParameter(new QName("record"), Saxon.wrapNode(context.getSIP().getRecord()));
            wacl2acl.setParameter(new QName("acl-base"), new XdmAtomicValue(dir.toString()));
            if (this.hasParameter("default-account"))
                wacl2acl.setParameter(new QName("default-accounts"), this.params.get("default-account"));
            if (this.hasParameter("default-role"))
                wacl2acl.setParameter(new QName("default-roles"), this.params.get("default-role"));                
            wacl2acl.setSource(new StreamSource(dir +"/policy.sem"));
            destination = new XdmDestination();
            wacl2acl.setDestination(destination);
            wacl2acl.transform();
            Saxon.save(destination, new File(dir + "/policy.acl"));

            // convert intermediate ACl to XACM using ACL/ACL2XACML.xsl or an override
            XsltTransformer acl2xacml = null;
            if (this.hasParameter("acl2xacml")) {
                File x = new File(this.getParameter("acl2xacml"));
                logger.debug("acl2xacml["+x+"]");
                if (!x.exists()) {
                    logger.error("The stylesheet["+x+"] doesn't exist!");
                    return false;
                }
                if (!x.canRead()) {
                    logger.error("The stylesheet["+x+"] can't be read!");
                    return false;
                }
                acl2xacml =  Saxon.buildTransformer(x).load();
            } else {
                acl2xacml =  Saxon.buildTransformer(ACL.class.getResource("/ACL/ACL2XACML.xsl")).load();
            }
            acl2xacml.setMessageListener(listener);
            acl2xacml.setErrorListener(listener);
            acl2xacml.setParameter(new QName("acl-base"), new XdmAtomicValue(dir.toString()));
            if (roles != null)
                acl2xacml.setParameter(new QName("roles"), Saxon.buildDocument(new StreamSource(roles)));
            // additional parameters
            for (String param:this.params.keySet()) {
                if (param.startsWith("xsl-param-"))
                    acl2xacml.setParameter(new QName(param.replaceFirst("^xsl-param-","")), params.get(param));
            }            
            acl2xacml.setSource(new StreamSource(dir +"/policy.acl"));
            destination = new XdmDestination();
            acl2xacml.setDestination(destination);
            acl2xacml.transform();
        } catch (Exception e) {
            throw new DepositException("The creation of ACL files failed!", e);
        } finally {
            // restore the URL 
            if (org!=null)
                Saxon.getXsltCompiler().setURIResolver(org);
        }
        return true;
    }

    static class JarURIResolver implements URIResolver {
        
        private URIResolver resolver = null;
        private File xsl = null;
        
        public JarURIResolver(URIResolver resolver) {
            this(resolver,null);
        }
        
        public JarURIResolver(URIResolver resolver, File xsl) {
            this.resolver = resolver;
            this.xsl = xsl;
        }
        
        public Source resolve(String href,String base) throws TransformerException {
            logger.debug("resolve["+href+"]["+base+"]");
            if (href.equals("jar:acl2xacml.xsl")) {
                if (this.xsl!=null) {
                    logger.debug("resolve["+href+"]["+base+"] return file["+this.xsl+"]");
                    return new javax.xml.transform.stream.StreamSource(this.xsl);
                } else {
                    logger.debug("resolve["+href+"]["+base+"] return resource["+ACL.class.getResource("/ACL/ACL2XACML.xsl").toString()+"]");
                    return new javax.xml.transform.stream.StreamSource(ACL.class.getResource("/ACL/ACL2XACML.xsl").toString());
                }
            } else {
                return resolver.resolve(href,base);
            }
        }        
    }
}
