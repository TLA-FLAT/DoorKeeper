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
import javax.xml.transform.stream.StreamSource;
import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.XdmAtomicValue;
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
public class Owner extends AbstractAction {

    private static final Logger logger = LoggerFactory.getLogger(Owner.class.getName());

    @Override
    public boolean perform(Context context) throws DepositException {
        try {
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
            // create the dir
            File dir = new File(getParameter("dir", "./acl"));
            if (!dir.exists()) {
                FileUtils.forceMkdir(dir);
            }

            // convert policy N3 to TriX
            // https://jena.apache.org/documentation/io/
            Model model = ModelFactory.createDefaultModel() ;
            model.read(policy.getAbsolutePath()) ;
            OutputStream trix = new FileOutputStream(new File(dir +"/policy.trix"));
            RDFDataMgr.write(trix, model, Lang.TRIX);

            // convert trix to semantic triples using ACL/sl-trix-to-sem-triples.xsl
            XsltTransformer trix2sem = Saxon.buildTransformer(Owner.class.getResource("/ACL/sl-trix-to-sem-triples.xsl")).load();
            SaxonListener listener = new SaxonListener("ACL",MDC.get("sip"));
            trix2sem.setMessageListener(listener);
            trix2sem.setErrorListener(listener);
            trix2sem.setSource(new StreamSource(dir +"/policy.trix"));
            // convert sem triples to an intermediate ACL using ACL/WebACL2ACL.xsl
            XsltTransformer wacl2acl = Saxon.buildTransformer(Owner.class.getResource("/ACL/owner.xsl")).load();
            wacl2acl.setMessageListener(listener);
            wacl2acl.setErrorListener(listener);
            wacl2acl.setParameter(new QName("record"), Saxon.wrapNode(context.getSIP().getRecord()));
            wacl2acl.setParameter(new QName("acl-base"), new XdmAtomicValue(dir.toString()));
                
        } catch (Exception e) {
            throw new DepositException("The creation of the owner file failed!", e);
        }
        return true;
    }

}
