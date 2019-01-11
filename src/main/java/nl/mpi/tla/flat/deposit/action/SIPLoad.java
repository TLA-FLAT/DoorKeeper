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

import java.io.File;
import nl.mpi.tla.flat.deposit.Context;
import nl.mpi.tla.flat.deposit.DepositException;
import nl.mpi.tla.flat.deposit.sip.cmdi.CMD;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 *
 * @author menzowi
 * @author pavsri
 */
public class SIPLoad extends AbstractAction {
    
    private static final Logger logger = LoggerFactory.getLogger(SIPLoad.class.getName());
    
    @Override
    public boolean perform(Context context) throws DepositException {
    	String namespace = context.getProperty("fedoraNamespace", "lat").toString();
        String sip = this.getParameter("sip","./metadata/record.cmdi");
        if (sip == null) {
            logger.error("no sip file specified!");
            return false;
        }

        File mr = new File(sip);
        if (!mr.isFile()) {
            logger.error("record["+mr.getAbsolutePath()+"] is not a file!");
            return false;
        }
        if (!mr.canRead()) {
            logger.error("record["+mr.getAbsolutePath()+"] file cannot be read!");
            return false;
        }
        if (!mr.canWrite()) {
            logger.error("work["+mr.getAbsolutePath()+"] file cannot be written!");
            return false;
        }

        logger.debug("SIP["+mr.getAbsolutePath()+"]");
        context.setSIP(new CMD(mr,namespace));

        return true;
    }
    
}
