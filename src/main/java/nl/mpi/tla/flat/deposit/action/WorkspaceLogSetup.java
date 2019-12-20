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

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.util.StatusPrinter;
import java.io.File;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.UUID;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.XdmAtomicValue;
import net.sf.saxon.s9api.XdmDestination;
import net.sf.saxon.s9api.XsltTransformer;
import nl.mpi.tla.flat.deposit.Context;
import nl.mpi.tla.flat.deposit.util.Saxon;
import nl.mpi.tla.flat.deposit.util.SaxonListener;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 *
 * @author menzowi
 */
public class WorkspaceLogSetup extends AbstractAction {
    
    private static final Logger logger = LoggerFactory.getLogger(WorkspaceLogSetup.class.getName());
    
    @Override
    public boolean perform(Context context) {
        if (MDC.get("sip")==null)
            MDC.put("sip","SIP_"+UUID.randomUUID());

        try {
            File config = null;
            if (this.hasParameter("logConfig")) {
                config = Paths.get(this.getParameter("logConfig")).toFile();
                if (!config.exists()) {
                    logger.error("The log config["+config+"] doesn't exist!");
                    return false;
                } else if (!config.isFile()) {
                    logger.error("The log config["+config+"] isn't a file!");
                    return false;
                } else if (!config.canRead()) {
                    logger.error("The log config["+config+"] dan't be read!");
                    return false;
                }
            }
                        
            File dir = new File(getParameter("dir","./logs"));
            if (!dir.exists())
                FileUtils.forceMkdir(dir);
            
            File logback = dir.toPath().resolve("./logback.xml").toFile();
            
            if (!logback.exists()) {
                // create {$work}/logs/logback-dk.xml
                logback = dir.toPath().resolve("./logback-dk.xml").toFile();
                if (logback.exists())
                    logback.delete();
                
                XsltTransformer configure = Saxon.buildTransformer(WorkspaceLogSetup.class.getResource("/WorkspaceLog/config-log.xsl")).load();
                SaxonListener listener = new SaxonListener("WorkspaceLogSetup", MDC.get("sip"));
                configure.setMessageListener(listener);
                configure.setErrorListener(listener);

                configure.setParameter(new QName("dir"), new XdmAtomicValue(dir.toString()));
                configure.setParameter(new QName("sip"), new XdmAtomicValue(MDC.get("sip")));
                
                Source in = (config!=null?new StreamSource(config):new StreamSource(WorkspaceLogSetup.class.getResource("/WorkspaceLog/empty-config.xml").toString()));
                configure.setSource(in);
                
                XdmDestination destination = new XdmDestination();
                configure.setDestination(destination);
                
                configure.transform();
                
                Saxon.save(destination,logback);
                
                // copy user-log.xml to {$work}/logs/user-log.xml
                Files.copy(FOXCreate.class.getResourceAsStream("/WorkspaceLog/user-log.xml"), dir.toPath().resolve("./user-log.xml"));
                // copy log4j.dtd to {$work}/logs/
                Files.copy(FOXCreate.class.getResourceAsStream("/WorkspaceLog/log4j.dtd"), dir.toPath().resolve("./log4j.dtd"));
            }
            
            Logger logger = LoggerFactory.getLogger(nl.mpi.tla.flat.deposit.Flow.class);
            LoggerContext logctxt = (LoggerContext) LoggerFactory.getILoggerFactory();
            JoranConfigurator configurator = new JoranConfigurator();
            configurator.setContext(logctxt);
            configurator.doConfigure(logback.toString());
            StatusPrinter.printInCaseOfErrorsOrWarnings(logctxt);
            logger.debug("\n\n" +
                "\"Relax,\" said the DoorKeeper,\n" +
                "\"I'm programmed to receive.\n" +
                "You can check-out any time you like,\n" +
                "But you can never leave!\"\n"
            );
        } catch (Exception ex) {
            this.logger.error("Couldn't setup the deposit log!",ex);
            return false;
        }
        return true;
    }
    
}
