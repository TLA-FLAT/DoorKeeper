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

import org.fcrepo.client.*;
import java.io.File;
import java.io.FilenameFilter;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import javax.xml.transform.stream.StreamSource;
import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.XdmItem;
import net.sf.saxon.s9api.XdmNode;
import nl.mpi.tla.flat.deposit.Context;
import nl.mpi.tla.flat.deposit.DepositException;
import static nl.mpi.tla.flat.deposit.action.FedoraAction.fedoraClient;
import nl.mpi.tla.flat.deposit.sip.Collection;
import nl.mpi.tla.flat.deposit.sip.Resource;
import nl.mpi.tla.flat.deposit.sip.SIPInterface;
import static nl.mpi.tla.flat.deposit.util.Global.NAMESPACES;
import nl.mpi.tla.util.Saxon;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.filefilter.RegexFileFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import nl.mpi.tla.flat.deposit.util.Global;
import org.apache.commons.configuration.XMLConfiguration;

/**
 * @author menzowi
 * @author pavsri
 */
public class FedoraTransaction extends FedoraAction {

	private static final Logger logger = LoggerFactory.getLogger(FedoraTransaction.class.getName());

	@Override
	public boolean perform(Context context) throws DepositException {
            SIPInterface sip;
            
            try {
                fedoraConfig = new XMLConfiguration(new File(getParameter("fedoraConfig")));               
            } catch(Exception e) {
                throw new DepositException("Connecting to Fedora Commons failed!",e);
            }
            try {
                connect(context);
                //sip = context.getSIP();

                // TODO look at the action name via this.getName() and determine what to do 
                // store the transaction response uri via context.putInMemory

                String actionName = this.getName(); 

                if ("startTransaction".equals(actionName)) {
                    logger.debug("Inside startTransaction.......");
                    try {
                        URI startUri = URI.create(fedoraConfig.getString("localServer")+"/fcr:tx");
                        logger.debug("StartUri--"+startUri);
                        TransactionalFcrepoClient transClient = fedoraClient.startTransactionClient(startUri);
                        URI transLocation = transClient.getTransactionURI();
                        context.putInMemory("transLocation",transLocation);
                        logger.debug("TransLocaton: "+transLocation);
                        logger.debug("TransLocaton context: "+context.getFromMemory("transLocation"));
                        context.registerRollbackEvent(this,"TransLocation","putInMemory",transLocation.toString());
                    }
                    catch (Exception e) {
                        throw new DepositException("Start Transaction Error: ", e);
                    }
                }
                    
                if ("commitTransaction".equals(actionName)) {
                    
                    //bring uri from the context memory and then append fcr:commit to it. 
                    String contextUri = context.getFromMemory("transLocation").toString();
                    logger.debug("Commit-- ContextUri--->"+contextUri);
                    URI commitUri = URI.create(contextUri+"/fcr:commit");
                    try (FcrepoResponse response = new PostBuilder(commitUri, fedoraClient).perform()) {
                        logger.debug("Transaction commit status: {}", response.getStatusCode());
                    }
                    catch (Exception e) {
                        throw new DepositException("Commit Transaction Error: ", e);
                    }
                }
                    
                if ("rollbackTransaction".equals(actionName)) {
                    //use the uri from the memory and append fcr:rollback to it
                    String contextUri = context.getFromMemory("transLocation").toString();
                    logger.debug("rollback-- ContextUri--->"+contextUri);
                    URI rollbackUri = URI.create(contextUri+"/fcr:rollback");
                    try (FcrepoResponse response = new PostBuilder(rollbackUri, fedoraClient).perform()) {
                        logger.debug("Transaction rollback status: {}", response.getStatusCode());
                    }
                    catch (Exception e) {
                        throw new DepositException("Rollback Transaction Error: ", e);
                    }
                }
            } catch (Exception e) {
                    throw new DepositException("The actual deposit in Fedora failed!", e);
            }
            return true;
	}
        
	public void rollback(Context context, List<XdmItem> events) {
            if (events.size() > 0) {
                for (ListIterator<XdmItem> iter = events.listIterator(events.size()); iter.hasPrevious();) {
                        XdmItem event = iter.previous();
                        try {
                                String tpe = Saxon.xpath2string(event, "@type");
                                if(tpe=="TransLocation"){
                                    String contextUri = context.getFromMemory("transLocation").toString();
                                    logger.debug("rollback-- ContextUri--->"+contextUri);
                                    URI rollbackUri = URI.create(contextUri+"/fcr:rollback");
                                    try (FcrepoResponse response = new PostBuilder(rollbackUri, fedoraClient).perform()) {
                                        logger.debug("Transaction rollback status: {}", response.getStatusCode());
                                    }
                                    catch (Exception e) {
                                        throw new DepositException("Rollback Transaction Error: ", e);
                                    }
                                }
                        }
                        catch (Exception ex) {
                                logger.error("rollback action[" + this.getName() + "] event[" + event + "] failed!", ex);
                        }
                }
            }
                       
        }
}
