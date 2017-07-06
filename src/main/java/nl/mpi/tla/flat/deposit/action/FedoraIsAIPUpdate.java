/* 
 * Copyright (C) 2017 The Language Archive
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

import java.net.URI;
import nl.mpi.tla.flat.deposit.Context;
import nl.mpi.tla.flat.deposit.DepositException;
import nl.mpi.tla.flat.deposit.sip.SIPInterface;
import org.slf4j.LoggerFactory;

/**
 *
 * @author menzowi
 */
public class FedoraIsAIPUpdate extends FedoraAction {
    
    private static final org.slf4j.Logger logger = LoggerFactory.getLogger(FedoraIsAIPUpdate.class.getName());
    
    @Override
    public boolean perform(Context context) throws DepositException {       
        try {
            connect(context);
            
            SIPInterface sip = context.getSIP();
            if (sip.hasPID()) {
                // TODO: check if PID exists
                // TODO: check if PID refers to URI in FC
                // TODO: check if it refers to the latest version (lost update!)
                URI fid = lookupIdentifier(sip.getPID());
                if (fid!=null) {
                    // mark SIP as an update
                    sip.update();
                    logger.info("This SIP["+sip.getPID()+"] is an update of AIP["+fid+"]!");
                } else
                    logger.debug("This SIP["+sip.getBase()+"]["+sip.getPID()+"] has no FID in this FC!");
            } else
                logger.debug("This SIP["+sip.getBase()+"] has no PID!");
        } catch (DepositException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new DepositException(ex);
        }        
        return true;
    }    
}
