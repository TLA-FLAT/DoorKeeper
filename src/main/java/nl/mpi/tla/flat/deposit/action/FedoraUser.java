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
package nl.mpi.tla.flat.deposit.action;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.UUID;
import javax.xml.transform.stream.StreamSource;
import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.XdmAtomicValue;
import net.sf.saxon.s9api.XdmDestination;
import net.sf.saxon.s9api.XsltTransformer;
import nl.mpi.tla.flat.deposit.Context;
import nl.mpi.tla.flat.deposit.DepositException;
import nl.mpi.tla.flat.deposit.util.Saxon;
import nl.mpi.tla.flat.deposit.util.SaxonListener;
import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.io.FileUtils;
import org.apache.jena.ext.com.google.common.io.Files;
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
public class FedoraUser extends AbstractAction {

    private static final Logger logger = LoggerFactory.getLogger(FedoraUser.class.getName());

    @Override
    public boolean perform(Context context) throws DepositException {
        boolean res = true;
        try {
            // check for the user profile
            File user = new File(getParameter("user"));
            if (!user.exists()) {
                logger.error("The user profile doesn't exist!");
                return true;
            } else if (!user.isFile()) {
                logger.error("The user profile isn't a file!");
                return false;
            } else if (!user.canRead()) {
                logger.error("The user profile can't be read!");
                return false;
            }

            // check for the Fedora config
            File conf = new File(getParameter("fedoraConfig"));
            if (!conf.exists()) {
                logger.error("The fedora config doesn't exist!");
                return true;
            } else if (!conf.isFile()) {
                logger.error("The fedora config isn't a file!");
                return false;
            } else if (!conf.canRead()) {
                logger.error("The fedora config can't be read!");
                return false;
            }
            
            // check for the user-specific Fedora config
            File usr = new File(getParameter("userFedoraConfig"));
            if (usr.exists() && !usr.isFile()) {
                logger.error("The user-specific fedora config isn't a file!");
                return false;
            } else if (usr.exists() && !usr.canWrite()) {
                logger.error("The user-specific fedora config can't be written!");
                return false;
            }
            
            // get the user and the temp pass
            XMLConfiguration userProfile = new XMLConfiguration(user);
            //String pass = UUID.randomUUID().toString();
            
            // export the php script to a tmp dir
            File tmp = Files.createTempDir();
            File php = tmp.toPath().resolve("./pass.php").toFile();            
            FileUtils.copyURLToFile(FedoraUser.class.getResource("/FedoraUser/pass.php"), php);

            // exec the drush script
            ProcessBuilder pb = new ProcessBuilder(getParameter("drush"), "php-script", "pass", "--script-path="+tmp, userProfile.getString("name")/*, pass*/);
            pb.directory(new File(getParameter("drupal")));
            pb.redirectErrorStream(true);
            
            Process process = pb.start();
            InputStream is = process.getInputStream();
            InputStreamReader isr = new InputStreamReader(is);
            BufferedReader br = new BufferedReader(isr);
 
            logger.debug("Setting up the user-specific access to fedora:");
            String pass = null;
            String line;
            while ((line = br.readLine()) != null) {
                logger.debug(line);
                if (line.startsWith("PASS: "))
                    pass = line.replaceFirst("^PASS: ", "");
            }
            process.waitFor();
            if (process.exitValue()!=0) {
                logger.error("Failed to set up the user-specific access to fedora!");
                res = false;
            } else
                logger.debug("Setup the user-specific access to fedora");

            if (pass == null) {
                logger.error("Failed to set up the user-specific access to fedora! No pass :-(");
                res = false;
            }
            
            // clean up the tmp dir
            php.delete();
            tmp.delete();

            // embed the user in the Fedora config
            XsltTransformer embed = Saxon.buildTransformer(FedoraUser.class.getResource("/FedoraUser/config.xsl")).load();
            SaxonListener listener = new SaxonListener("FedoraUser",MDC.get("sip"));
            embed.setMessageListener(listener);
            embed.setErrorListener(listener);
            embed.setSource(new StreamSource(conf));
            embed.setParameter(new QName("user"), Saxon.buildDocument(new StreamSource(user)));
            embed.setParameter(new QName("pass"), new XdmAtomicValue(pass));
            XdmDestination destination = new XdmDestination();
            embed.setDestination(destination);
            embed.transform();
            
            Saxon.save(destination.getXdmNode().asSource(),usr);
                        
        } catch (Exception e) {
            throw new DepositException("Setting up the user-specific access to fedora failed!", e);
        }
        return res;
    }

}