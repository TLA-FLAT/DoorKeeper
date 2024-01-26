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
import java.nio.file.Paths;

import nl.mpi.tla.schemanon.SchemAnon;
import nl.mpi.tla.util.Saxon;

import nl.mpi.tla.flat.deposit.Context;
import nl.mpi.tla.flat.deposit.DepositException;
import static nl.mpi.tla.flat.deposit.util.Global.NAMESPACES;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;

/**
 *
 * @author menzowi
 */
public class Validate extends AbstractAction {
    
    private static final Logger logger = LoggerFactory.getLogger(Validate.class.getName());
    
    @Override
    public boolean perform(Context context) throws DepositException {
        try {
            String schemaCache = getParameter("schemaCache","./cache");
            String rules = getParameter("rules");
            
            File cache = new File(schemaCache);
            if (!cache.exists())
                 FileUtils.forceMkdir(cache);
            
            Document rec = context.getSIP().getRecord();
            String xsd = Saxon.xpath2string(Saxon.wrapNode(rec), "/*/@xsi:schemaLocation", null, NAMESPACES).replaceAll(".* ","");
            logger.debug("XSD schema location["+xsd+"]");

            return false;
        } catch (Exception ex) {
            throw new DepositException(ex);
        }
    }
}


