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

import nl.mpi.tla.flat.deposit.Context;
import nl.mpi.tla.flat.deposit.DepositException;
import nl.mpi.tla.flat.deposit.sip.Collection;
import nl.mpi.tla.flat.deposit.sip.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author menzowi
 */
public class HandleCreation extends AbstractAction {
    
    private static final Logger logger = LoggerFactory.getLogger(HandleCreation.class.getName());

    @Override
    public boolean perform(Context context) throws DepositException {
        
        String fedora = this.getParameter("fedoraServer");
        
        if (context.getSIP().hasPID() && context.getSIP().hasFID()) {

            String fid = context.getSIP().getFID().toString().replaceAll("#.*","");
            String dsid = context.getSIP().getFID().getRawFragment().replaceAll("@.*","");
            String asof = context.getSIP().getFID().getRawFragment().replaceAll(".*@","");

            logger.info("Create handle["+context.getSIP().getPID()+"] -> URI["+fedora+"/objects/"+fid+"/datastreams/"+dsid+"/content?asOfDateTime="+asof+"]");
        } else {
            if (!context.getSIP().hasPID())
                logger.debug("SIP has no PID!");
            if (!context.getSIP().hasFID())
                logger.debug("SIP has no FID!");
        }
        
        for (Collection col:context.getSIP().getCollections(true)) {
            if (col.hasPID() && col.hasFID()) {
                String cfid = col.getFID().toString().replaceAll("#.*","");
                String cdsid = col.getFID().getRawFragment().replaceAll("@.*","");
                String casof = col.getFID().getRawFragment().replaceAll(".*@","");

                logger.info("Create or update handle["+col.getPID()+"] -> URI["+fedora+"/objects/"+cfid+"/datastreams/"+cdsid+"/content?asOfDateTime="+casof+"]");
            } else {
                if (!col.hasPID())
                    logger.debug("Collection["+col+"] has no PID!");
                if (!col.hasFID())
                    logger.debug("Collection["+col+"] has no FID!");
            }
        }
        
        for (Resource res:context.getSIP().getResources()) {
            if (res.hasPID() && res.hasFID()) {
                String rfid = res.getFID().toString().replaceAll("#.*","");
                String rdsid = res.getFID().getRawFragment().replaceAll("@.*","");
                String rasof = res.getFID().getRawFragment().replaceAll(".*@","");

                logger.info("Create handle["+res.getPID()+"] -> URI["+fedora+"/objects/"+rfid+"/datastreams/"+rdsid+"/content?asOfDateTime="+rasof+"]");
            } else {
                if (!res.hasPID())
                    logger.debug("Resource["+res+"] has no PID!");
                if (!res.hasFID())
                    logger.debug("Resource["+res+"] has no FID!");
            }
        }
        
        return true;
    }
    
}
