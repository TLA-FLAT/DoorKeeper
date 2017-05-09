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
import java.io.IOException;
import java.net.URI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.handle.hdllib.HandleException;
import nl.mpi.handle.util.HandleManager;
import nl.mpi.tla.flat.deposit.Context;
import nl.mpi.tla.flat.deposit.DepositException;
import nl.mpi.tla.flat.deposit.sip.Resource;
import nl.mpi.tla.flat.deposit.action.handle.util.HandleManagerFactory;
import nl.mpi.tla.flat.deposit.sip.Collection;

public class TLAHandleCreation extends AbstractAction {

    private static final Logger logger = LoggerFactory.getLogger(TLAHandleCreation.class);

    @Override
    public boolean perform(Context context) throws DepositException {

        boolean allSuccessful = true;

        String fedoraServer = this.getParameter("fedoraServer");
        String handlePrefix = this.getParameter("handlePrefix");
        String handleAdminKeyFilePath = this.getParameter("handleAdminKeyFilePath");
        String handleAdminUserHandleIndex = this.getParameter("handleAdminUserHandleIndex");
        String handleAdminUserHandle = this.getParameter("handleAdminUserHandle");
        String handleAdminPassword = this.getParameter("handleAdminPassword");

        HandleManager handleManager = null;
        try {
            handleManager = HandleManagerFactory.getNewHandleManager(
                handlePrefix, handleAdminKeyFilePath, handleAdminUserHandleIndex, handleAdminUserHandle, handleAdminPassword);
        } catch (IOException ex) {
            StringBuilder message = new StringBuilder("Could not instantiate HandleManager");
            throwDepositException(message, ex);
        }

        if (context.getSIP().hasPID() && context.getSIP().hasFID()) {
            String sipFid = context.getSIP().getFID().toString().replaceAll("#.*","");
            String sipDsid = context.getSIP().getFID().getRawFragment().replaceAll("@.*","");
            String sipAsof = context.getSIP().getFID().getRawFragment().replaceAll(".*@","");
            URI sipHandleTarget = URI.create(fedoraServer + "/objects/" + sipFid + "/datastreams/" + sipDsid + "/content?asOfDateTime=" + sipAsof);
            File sipBase = context.getSIP().getBase();
            URI sipPid = context.getSIP().getPID();

            logger.info("Creating handle[" + sipPid + "] -> URI[" + sipHandleTarget + "]");

            try {
                handleManager.assignHandle(sipBase, sipPid, sipHandleTarget);
            } catch (HandleException | IOException ex) {
                StringBuilder message = new StringBuilder("Error assigning handle '").append(context.getSIP().getPID()).append("', of SIP '").append(context.getSIP().getFID()).append("', to target '").append(sipHandleTarget).append("'.");
                throwDepositException(message, ex);
            }
        } else {
            if (!context.getSIP().hasPID())
                logger.debug("SIP has no PID!");
            if (!context.getSIP().hasFID())
                logger.debug("SIP has no FID!");
        }

        for (Collection col : context.getSIP().getCollections(true)) {
            if (col.hasPID() && col.hasFID()) {
                String resFid = col.getFID().toString().replaceAll("#.*","");
                String resDsid = col.getFID().getRawFragment().replaceAll("@.*","");
                String resAsof = col.getFID().getRawFragment().replaceAll(".*@","");
                URI colHandleTarget = URI.create(fedoraServer + "/objects/" + resFid + "/datastreams/" + resDsid + "/content?asOfDateTime=" + resAsof);

                try {
                    URI colPid = col.getPID();

                    // TODO: check if handle exists already
                    // no : create handle = existing code
                    // yes: update handle =  new code
                    logger.info("Creating handle[" + colPid + "] -> URI[" + colHandleTarget + "]");

                    handleManager.assignHandle(null,colPid, colHandleTarget);
                } catch (HandleException | IOException ex) {
                    StringBuilder message = new StringBuilder("Error assigning handle '").append(col.getPID()).append("', of resource '").append(col.getFID()).append("', to target '").append(colHandleTarget).append("'.");
                    logger.error(message.toString(), ex);
                    allSuccessful = false;
                } catch (DepositException ex) {
                    logger.error(ex.getMessage(), ex);
                    allSuccessful = false;
                }
            } else {
                if (!col.hasPID())
                    logger.debug("Collection["+col+"] has no PID!");
                if (!col.hasFID())
                    logger.debug("Collection["+col+"] has no FID!");
            }
        }

        for (Resource res : context.getSIP().getResources()) {
            if (res.hasPID() && res.hasFID()) {
                String resFid = res.getFID().toString().replaceAll("#.*","");
                String resDsid = res.getFID().getRawFragment().replaceAll("@.*","");
                String resAsof = res.getFID().getRawFragment().replaceAll(".*@","");
                URI resHandleTarget = URI.create(fedoraServer + "/objects/" + resFid + "/datastreams/" + resDsid + "/content?asOfDateTime=" + resAsof);
                File resFile = res.getFile();

                try {
                    URI resPid = res.getPID();

                    logger.info("Creating handle[" + resPid + "] -> URI[" + resHandleTarget + "]");

                    handleManager.assignHandle(resFile, resPid, resHandleTarget);
                } catch (HandleException | IOException ex) {
                    StringBuilder message = new StringBuilder("Error assigning handle '").append(res.getPID()).append("', of resource '").append(res.getFID()).append("', to target '").append(resHandleTarget).append("'.");
                    logger.error(message.toString(), ex);
                    allSuccessful = false;
                } catch (DepositException ex) {
                    logger.error(ex.getMessage(), ex);
                    allSuccessful = false;
                }
            } else {
                if (!res.hasPID())
                    logger.debug("Resource["+res+"] has no PID!");
                if (!res.hasFID())
                    logger.debug("Resource["+res+"] has no FID!");
            }
        }

        return allSuccessful;
    }

	
    private void throwDepositException(StringBuilder message, Exception cause) throws DepositException {
        logger.error(message.toString(), cause);
        throw new DepositException(message.toString(), cause);
    }
}
