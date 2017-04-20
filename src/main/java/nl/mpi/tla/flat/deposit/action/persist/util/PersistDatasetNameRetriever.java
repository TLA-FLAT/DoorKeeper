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
package nl.mpi.tla.flat.deposit.action.persist.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;

import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.XdmNode;
import nl.mpi.tla.flat.deposit.DepositException;
import nl.mpi.tla.flat.deposit.util.Global;
import nl.mpi.tla.flat.deposit.util.Saxon;

/**
 * Class used to retrieve the name to use for the dataset base folder.
 * @author guisil
 */
public class PersistDatasetNameRetriever {
	
	private static Logger logger = LoggerFactory.getLogger(PersistDatasetNameRetriever.class);

	/**
	 * Gets the dataset name by using the given xpath expression on the given SIP record.
	 * @param sipRecord SIP record to get the value from
	 * @param datasetNameXpath XPATH expression to use 
	 * @return name to use as base for the dataset folder
	 */
	public String getDatasetName(Document sipRecord, String datasetNameXpath) throws DepositException {

            XdmNode sipNode = Saxon.wrapNode(sipRecord);
            String datasetName;
            try {
                datasetName = Saxon.xpath2string(sipNode, datasetNameXpath, null, Global.NAMESPACES);
            } catch (SaxonApiException ex) {
                String message = "Error extracting name to use as base folder for the resource policy";
                logger.error(message, ex);
                throw new DepositException(message, ex);
            }

            return datasetName;
	}
}
