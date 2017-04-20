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

import com.yourmediashelf.fedora.client.FedoraClient;
import static com.yourmediashelf.fedora.client.FedoraClient.*;
import com.yourmediashelf.fedora.client.FedoraCredentials;
import com.yourmediashelf.fedora.client.request.FedoraRequest;
import com.yourmediashelf.fedora.client.response.IngestResponse;
import com.yourmediashelf.fedora.client.response.GetDatastreamResponse;
import java.io.File;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.util.Collection;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import nl.mpi.tla.flat.deposit.Context;
import nl.mpi.tla.flat.deposit.DepositException;
import org.apache.commons.configuration.XMLConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

abstract public class FedoraAction extends AbstractAction {

    private static final Logger logger = LoggerFactory.getLogger(FedoraAction.class.getName());
    
    private XMLConfiguration fedoraConfig = null;

    public void connect(Context context) throws DepositException {
        try {
            fedoraConfig = new XMLConfiguration(new File(getParameter("fedoraConfig")));        

            logger.debug("Fedora Commons["+fedoraConfig.getString("localServer")+"]["+fedoraConfig.getString("userName")+":"+fedoraConfig.getString("userPass")+"]");
            if (!FedoraRequest.isDefaultClientSet()) {
                FedoraCredentials credentials = new FedoraCredentials(fedoraConfig.getString("localServer"), fedoraConfig.getString("userName"), fedoraConfig.getString("userPass"));
                FedoraClient fedora = new FedoraClient(credentials);
                fedora.debug(true);
                FedoraRequest.setDefaultClient(fedora);
            }
            logger.debug("Fedora Commons repository["+FedoraClient.describeRepository().xml(true).execute()+"]");            
        } catch(Exception e) {
            throw new DepositException("Connecting to Fedora Commons failed!",e);
        }
    }
    
}
