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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import nl.mpi.tla.flat.deposit.action.fedoratransaction.util.FedoraTransactionThread;

/**
 * @author menzowi
 * @author pavsri
 */
public class FedoraTransaction extends FedoraAction {

	private static final Logger logger = LoggerFactory.getLogger(FedoraTransaction.class.getName());
        private static final String KEEP_ALIVE_TASK = "fedoraTransactionKeepAliveTask";
        private static final String KEEP_ALIVE_EXECUTOR = "fedoraTransactionKeepAliveExecutor";

	@Override
	public boolean perform(Context context) throws DepositException {
            SIPInterface sip;
            
            connect(context);
           
            try {
                fedoraConfig = new XMLConfiguration(new File(getParameter("fedoraConfig")));               
            } catch(Exception e) {
                throw new DepositException("Connecting to Fedora Commons failed!",e);
            }
            try {
                String actionName = this.getName(); 

                if ("startTransaction".equals(actionName)) {
                    ScheduledExecutorService scheduler = null;
                    try {
                        URI startUri = URI.create(fedoraConfig.getString("localServer")+"/fcr:tx");
                        logger.debug("StartUri--"+startUri);
                        TransactionalFcrepoClient transClient = fedoraClient.startTransactionClient(startUri);
                        URI transLocation = transClient.getTransactionURI();
                        context.putInMemory("transLocation",transLocation);
                        logger.debug("Transaction Location from context memory: "+context.getFromMemory("transLocation"));
                        context.registerRollbackEvent(this,"TransLocation","putInMemory",transLocation.toString());
                        
                        //keeping the transaction alive by threading
                        scheduler = Executors.newSingleThreadScheduledExecutor();
                        ScheduledFuture<?> thread = scheduler.scheduleAtFixedRate(
                                new FedoraTransactionThread(transLocation.toString(),fedoraClient),
                                0,            // initial delay
                                1,            // period
                                TimeUnit.MINUTES
                        );
                        context.putInMemory(KEEP_ALIVE_TASK, thread);
                        context.putInMemory(KEEP_ALIVE_EXECUTOR, scheduler);
                        logger.debug("Thread has been started succussfully! :"+thread);
                    }
                    catch (Exception e) {
                        if (scheduler != null)
                            scheduler.shutdownNow();
                        throw new DepositException("Start Transaction Error: ", e);
                    }
                }
                    
                if ("commitTransaction".equals(actionName)) {
                    String contextUri = context.getFromMemory("transLocation").toString();
                    logger.debug("Commit-- ContextUri--->"+contextUri);
                    URI commitUri = URI.create(contextUri);
                    try (FcrepoResponse response = new PutBuilder(commitUri, fedoraClient).perform()) {
                        logger.debug("Transaction commit status: {}", response.getStatusCode());
                        
                        if (response.getStatusCode() >= 300) {
                            logger.error("Commit Transaction statuscode:"+response.getStatusCode());
                            throw new DepositException("Commit Transaction Error: statuscode "+ response.getStatusCode()); 
                        }
                        else { 
                                logger.debug("Successfully committed the trasaction!");  
                                stopKeepAlive(context);
                        }
                    }
                    catch (Exception e) {
                        throw new DepositException("Commit Transaction Error: ", e);
                    } finally {
                        stopKeepAlive(context);
                    }
                }
            } catch (Exception e) {
                    throw new DepositException("The actual deposit in Fedora failed!", e);
            }
            return true;
	}
        
        public void rollback(Context context,List<XdmItem> events) {
            for (ListIterator<XdmItem> iter = events.listIterator(events.size());iter.hasPrevious();) {
                XdmItem event = iter.previous();
                try {
                        String tpe = Saxon.xpath2string(event, "@type");
                        if(tpe.equals("TransLocation")){
                            String contextUri = context.getFromMemory("transLocation").toString();
                            logger.debug("rollback-- ContextUri--->"+contextUri);
                            URI rollbackUri = URI.create(contextUri);
                            try (FcrepoResponse response = new DeleteBuilder(rollbackUri, fedoraClient).perform()) {
                                logger.debug("Transaction rollback status: {}", response.getStatusCode());
                                switch (response.getStatusCode()) {
                                    case 404 -> logger.error("Rollback Transaction statuscode:"+response.getStatusCode()+"-> Not Found: if the transaction doesn't exist");
                                    case 410 -> logger.error("Rollback Transaction statuscode:"+response.getStatusCode()+"-> Gone: if the transaction has already been committed or rolled back");
                                    default -> {
                                        logger.debug("Successfully rolled the trasaction back!");
                                    }
                                }
                            }
                            catch (Exception e) {
                                throw new DepositException("Rollback Transaction Error: ", e);
                            } finally {
                                stopKeepAlive(context);
                            }
                        }
                    }
                    catch (Exception ex) {
                            stopKeepAlive(context);
                            logger.error("rollback action[" + this.getName() + "] event[" + event + "] failed!", ex);
                    }
            }
        }

        private void stopKeepAlive(Context context) {
            if (context.hasInMemory(KEEP_ALIVE_TASK)) {
                ScheduledFuture<?> task = (ScheduledFuture<?>) context.remove(KEEP_ALIVE_TASK);
                task.cancel(true);
            }
            if (context.hasInMemory(KEEP_ALIVE_EXECUTOR)) {
                ScheduledExecutorService scheduler = (ScheduledExecutorService) context.remove(KEEP_ALIVE_EXECUTOR);
                scheduler.shutdownNow();
            }
        }
}
