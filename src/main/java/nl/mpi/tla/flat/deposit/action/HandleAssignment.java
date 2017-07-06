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

import java.net.URI;
import java.util.UUID;
import nl.mpi.tla.flat.deposit.Context;
import nl.mpi.tla.flat.deposit.DepositException;
import nl.mpi.tla.flat.deposit.sip.Resource;
import nl.mpi.tla.flat.deposit.sip.SIPInterface;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author menzowi
 */
public class HandleAssignment extends AbstractAction {
    
    private static final Logger logger = LoggerFactory.getLogger(HandleAssignment.class.getName());

    @Override
    public boolean perform(Context context) throws DepositException {
        try {
            
            if (!hasParameter("prefix"))
                throw new DepositException("Handle prefix has not been specified!");
            
            SIPInterface sip = context.getSIP();
            URI pid = (sip.hasPID()?sip.getPID():null);
            sip.setPID(new URI("hdl:"+getParameter("prefix")+"/"+UUID.randomUUID()));
            if (pid==null) {
                logger.info("Assigned new PID["+sip.getPID()+"] to the SIP");
            } else {
                logger.info("Assigned new PID["+sip.getPID()+"] to the SIP to update AIP["+pid+"]");
            }
            for (Resource res:sip.getResources()) {
                if (res.isInsert() || res.isUpdate()) {
                    if (res.isInsert() && res.hasPID()) {
                        logger.info("Retained existing PID["+res.getPID()+"] for Resource["+res.getURI()+"]");
                    } else {
                        res.setPID(new URI("hdl:"+getParameter("prefix")+"/"+UUID.randomUUID()));
                        logger.info("Assigned new PID["+res.getPID()+"] to Resource["+res.getURI()+"]");
                    }
                }
            }
        } catch (Exception ex) {
            throw new DepositException("Couldn't assign PIDs!",ex);
        }
        return true;
    }
    
}
