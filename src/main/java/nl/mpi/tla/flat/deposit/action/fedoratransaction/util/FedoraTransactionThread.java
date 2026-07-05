
package nl.mpi.tla.flat.deposit.action.fedoratransaction.util;

import java.net.URI;
import java.util.logging.Level;
import nl.mpi.tla.flat.deposit.DepositException;
import nl.mpi.tla.flat.deposit.action.FedoraTransaction;
import org.fcrepo.client.FcrepoClient;
import org.fcrepo.client.FcrepoResponse;
import org.fcrepo.client.PostBuilder;
import org.slf4j.LoggerFactory;

/**
 *
 * @author pavsri
 */
public class FedoraTransactionThread implements Runnable {
    
    private static final org.slf4j.Logger logger = LoggerFactory.getLogger(FedoraTransactionThread.class.getName());
    private String Uri;
    private FcrepoClient fedoraClient;
    
    public FedoraTransactionThread (String uri, FcrepoClient fedoraClientArg) throws DepositException {
     this.Uri = uri; 
     this.fedoraClient= fedoraClientArg;
    }
    
    public void run() {
            logger.debug("Task running at: " + System.currentTimeMillis());
            URI keepAliveUri = URI.create(this.Uri);
            try (FcrepoResponse response = new PostBuilder(keepAliveUri, this.fedoraClient).perform()) {
                logger.debug("Transaction kept alive status: {}", response.getStatusCode());
                switch (response.getStatusCode()) {
                    case 404 -> logger.error("Keep Alive Transaction statuscode:"+response.getStatusCode()+"-> Not Found: if the transaction doesn't exist");
                    case 410 -> logger.error("Keep Alive Transaction statuscode:"+response.getStatusCode()+"-> Gone: TRANSACTION EXPIRED");
                    default -> {
                        logger.debug("Successfully transaction is renewed successfully!");
                    }
                }
            } catch (Exception ex) {
                java.util.logging.Logger.getLogger(FedoraTransaction.class.getName()).log(Level.SEVERE, null, ex);
            } 
        }
    
}
