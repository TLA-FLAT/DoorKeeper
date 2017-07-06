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
import javax.xml.transform.stream.StreamSource;
import net.sf.saxon.s9api.XdmDestination;
import net.sf.saxon.s9api.XsltTransformer;
import nl.mpi.tla.flat.deposit.Context;
import nl.mpi.tla.flat.deposit.DepositException;
import nl.mpi.tla.flat.deposit.sip.Resource;
import nl.mpi.tla.flat.deposit.sip.SIPInterface;
import nl.mpi.tla.flat.deposit.util.Saxon;
import nl.mpi.tla.flat.deposit.util.SaxonListener;
import org.apache.jena.ext.com.google.common.io.Files;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 *
 * @author menzowi
 */
public class FOXUpdate extends AbstractAction {
    
    private static final Logger logger = LoggerFactory.getLogger(FOXUpdate.class.getName());

    @Override
    public boolean perform(Context context) throws DepositException {
        boolean debug = this.getParameter("debug", "false").equals("true");
        try {
            File dir = new File(getParameter("dir","./fox"));
            if (!dir.exists())
                throw new DepositException("The directory["+dir+"] with FOX files doesn't exist!");

            if (context.getSIP().isUpdate()) {
                // FC DOs already exist, so we need to get the updates out of the FOXML

                SIPInterface sip = context.getSIP();

                // SIP
                // - insert
                //   -> keep FOXML
                // - update
                //   -> split
                //   -> delete FOXML
                File fox = new File(dir + "/"+sip.getFID().toString().replaceAll("[^a-zA-Z0-9]", "_")+"_CMD.xml");
                if (!fox.exists())
                    throw new DepositException("The FOX file["+fox.getAbsolutePath()+"] for the SIP doesn't exist!");
                if (!fox.canRead())
                    throw new DepositException("The FOX file["+fox.getAbsolutePath()+"] for the SIP can't be read!");
            
                XsltTransformer split = Saxon.buildTransformer(FOXUpdate.class.getResource("/FOXUpdate/splitFOX.xsl")).load();
                SaxonListener listener = new SaxonListener("FOXUpdate",MDC.get("sip"));
                split.setMessageListener(listener);
                split.setErrorListener(listener);
                split.setSource(new StreamSource(fox));
                XdmDestination destination = new XdmDestination();
                split.setDestination(destination);
                split.transform();
                
                if (debug)
                    Files.copy(fox, new File(fox.toString().replace(".xml", ".bak")));
                if (!fox.delete())
                    throw new DepositException("The superfluous FOX file["+fox.getAbsolutePath()+"] for the updated SIP can't be deleted!");

            
                for (Resource res:sip.getResources()) {
                    // Resource
                    // - insert
                    //   -> keep FOXML
                    // - update
                    //   -> split
                    //   -> delete FOXML
                    // - noop
                    //   -> delete FOXML
                    fox = new File(dir + "/"+res.getFID().toString().replaceAll("[^a-zA-Z0-9]", "_")+".xml");
                    if (!fox.exists()) {
                        if  (res.isInsert() || res.isUpdate())
                            throw new DepositException("The FOX file["+fox.getAbsolutePath()+"] for the Resource["+res.getURI()+"] doesn't exist!");
                    } else if (!fox.canRead())
                        throw new DepositException("The FOX file["+fox.getAbsolutePath()+"] for the Resource["+res.getURI()+"] can't be read!");
                    
                    if (res.isUpdate()) {
                        split.setSource(new StreamSource(fox));
                        destination = new XdmDestination();
                        split.setDestination(destination);
                        split.transform();
                        if (debug)
                            Files.copy(fox, new File(fox.toString().replace(".xml", ".bak")));
                        if (!fox.delete())
                            throw new DepositException("The superfluous FOX file["+fox.getAbsolutePath()+"] for the updated Resource["+res.getURI()+"] can't be deleted!");
                    } else if (fox.exists() && !res.isInsert()) {
                        if (debug)
                            Files.copy(fox, new File(fox.toString().replace(".xml", ".bak")));
                        if (!fox.delete())
                            throw new DepositException("The superfluous FOX file["+fox.getAbsolutePath()+"] for the noop/delete Resource["+res.getURI()+"] can't be deleted!");
                    }
                }
            }
        } catch(Exception e) {
            throw new DepositException("The splitting of FOX file into DS updates failed!",e);
        }
        return true;
    }
    
}
