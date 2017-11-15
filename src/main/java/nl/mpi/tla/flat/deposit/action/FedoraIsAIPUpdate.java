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
import java.util.Date;
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
            
            URI pid = null;
            if (sip.hasPID()) {
                pid = sip.getPID();
            } else if (this.getParameter("requirePID","true").equals("false") && sip.hasFID()) {
                // we have a FID and its allowed to lookup the PID (of the last version).
                // WARN: this might lead to lost updates!
                pid = this.lookupFID(sip.getFID(true));
            }
            if (pid!=null) {
                URI fid = lookupFID(pid);
                if (fid!=null) {
                    // mark SIP as an update
                    sip.update();
                    // complete the FID
                    sip.setFIDasOfTimeDate(this.lookupAsOfDateTime(fid));
                    logger.info("This SIP["+pid+"] is an update of AIP["+fid+"]!");
                } else if (this.hasParameter("prefix") && pid.toString().startsWith("hdl:"+this.getParameter("prefix")+"/")) {
                    logger.error("This SIP["+pid+"] has a matching handle prefix["+this.getParameter("prefix")+"], but can't be found in the repository! It might be a PID for an old version!");
                    return false;
                } else {
                    logger.info("This SIP["+pid+"] doesn't exist in the repository! The PID will be overwritten!");
                }
            } else
                logger.debug("This SIP["+sip.getBase()+"] has no PID! A PID will be generated.");
        } catch (DepositException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new DepositException(ex);
        }        
        return true;
    }    
}
