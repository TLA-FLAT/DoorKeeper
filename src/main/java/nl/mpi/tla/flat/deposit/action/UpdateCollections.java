/*
 * Copyright (C) 2017 menzowi
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

import static com.yourmediashelf.fedora.client.FedoraClient.getDatastream;
import com.yourmediashelf.fedora.client.response.GetDatastreamResponse;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.XdmItem;
import nl.mpi.tla.flat.deposit.Context;
import nl.mpi.tla.flat.deposit.DepositException;
import nl.mpi.tla.flat.deposit.sip.Resource;
import nl.mpi.tla.flat.deposit.sip.SIPInterface;
import static nl.mpi.tla.flat.deposit.util.Global.NAMESPACES;
import nl.mpi.tla.flat.deposit.util.Saxon;
import org.slf4j.LoggerFactory;

/**
 *
 * @author menzowi
 */
public class UpdateCollections extends FedoraAction {
    
    private static final org.slf4j.Logger logger = LoggerFactory.getLogger(UpdateCollections.class.getName());
    
    @Override
    public boolean perform(Context context) throws DepositException {       
        try {
            connect(context);
            for (XdmItem resource:Saxon.xpath(Saxon.wrapNode(context.getSIP().getRecord()),"/cmd:CMD/cmd:Resources/cmd:IsPartOfList/cmd:IsPartOf/(@lat:flatURI,.)[1]",null,NAMESPACES)) {
                logger.debug("isPartOf collection["+resource.getStringValue()+"]");
                String fid = resource.getStringValue();
                if (fid.startsWith("lat:")) {
                    GetDatastreamResponse dsResponse = getDatastream(fid,"CMD").execute();
                    logger.debug("collection CMD["+dsResponse.getLastModifiedDate()+"]");
                }
            }
        } catch (Exception ex) {
            throw new DepositException(ex);
        }        
        return true;
    }
    
}
