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

import static com.yourmediashelf.fedora.client.FedoraClient.getDatastreamDissemination;
import com.yourmediashelf.fedora.client.response.FedoraResponse;
import nl.mpi.tla.flat.deposit.Context;
import nl.mpi.tla.flat.deposit.DepositException;
import nl.mpi.tla.flat.deposit.sip.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import java.util.Set;
import javax.xml.transform.stream.StreamSource;
import net.sf.saxon.s9api.XdmNode;
import nl.mpi.tla.flat.deposit.sip.SIPInterface;
import nl.mpi.tla.flat.deposit.sip.cmdi.CMDResource;
import nl.mpi.tla.flat.deposit.util.Global;
import nl.mpi.tla.flat.deposit.util.Saxon;

/**
 * This action sets the status to NOOP for local resources from which the checksums is equivalent to the one in the repository
 */
public class PurgeUpdates extends FedoraAction {

    private static final Logger logger = LoggerFactory.getLogger(PurgeUpdates.class.getName());

    @Override
    public boolean perform(Context context) throws DepositException {

        try {
            
            // connect to Fedora
            connect(context);
            
            String ext = getParameter("ext",".FITS.xml");
            String xp  = getParameter("path","normalize-space(//fits:md5checksum)");
            
            Path dir = Paths.get(getParameter("dir","./fits")).toAbsolutePath();
            if (!dir.toFile().exists())
                throw new DepositException("directory["+dir+"] doesn't exist!");
            if (!dir.toFile().isDirectory())
                throw new DepositException("directory["+dir+"] isn't a directory!");
            if (!dir.toFile().canRead())
                throw new DepositException("directory["+dir+"] can't be read!");
            
            SIPInterface sip = context.getSIP();
            Set<Resource> resources = context.getSIP().getResources();
            
            for (Resource res:resources) {
                if (res.isUpdate()) {
                    if (!res.hasFID())
                        throw new DepositException("Update of Resource["+res.getURI()+"] but location in the Repository is unknown!");
                    
                    // get local checksum
                    String name = res.getFile().getPath().replaceAll("[^a-zA-Z0-9\\-]", "_");
                    if (res instanceof CMDResource)
                        name = ((CMDResource)res).getID();
                    File f = dir.resolve("./"+name+ext).toFile();
                    logger.debug("file["+f.getAbsolutePath()+"] for Resource["+res.getURI()+"]");
                    if (!f.exists())
                        throw new DepositException("file for Resource["+res.getURI()+"] doesn't exist!");
                    if (!f.isFile())
                        throw new DepositException("file for Resource["+res.getURI()+"] isn't a file!");
                    if (!f.canRead())
                        throw new DepositException("file for Resource["+res.getURI()+"] can't be read!");
                    
                    XdmNode fd = Saxon.buildDocument(new StreamSource(f));
                    String checksum = Saxon.xpath2string(fd,xp,null,Global.NAMESPACES);
                    
                    if (checksum.isEmpty())
                        throw new DepositException("checksum for Resource["+res.getURI()+"] is unknown!");
                    
                    logger.debug("checksum["+checksum+"] for Resource["+res.getURI()+"]");
                    
                    // get repository checksum (from FC DO DC)                    
                    FedoraResponse resp = getDatastreamDissemination(res.getFID().toString(),"DC").execute();
                    if (resp.getStatus()!=200)
                        throw new DepositException("Unexpected status["+resp.getStatus()+"] while querying Fedora Commons!");
                        
                    XdmNode fc = Saxon.buildDocument(new StreamSource(resp.getEntityInputStream()));
                    logger.debug("DC["+fc.toString()+"]");
                    String fcChecksum = Saxon.xpath2string(fc, "normalize-space(//dc:identifier[starts-with(.,'md5:')])",null,Global.NAMESPACES).replace("md5:","");

                    if (fcChecksum.isEmpty())
                        throw new DepositException("Stored checksum for Resource["+res.getURI()+"] is unknown!");
                    
                    logger.debug("FC checksum["+fcChecksum+"] for Resource["+res.getURI()+"]");

                    if (fcChecksum.equals(checksum)) {
                        res.setStatus(Resource.Status.NOOP);
                        logger.debug("This Resource["+res.getPID()+"] is a noop of stored Resource["+res.getFID()+"]!");
                    } else
                        logger.info("This Resource["+res.getPID()+"] is an update of stored Resource["+res.getFID()+"]!");

                }
            }
            
        } catch (Exception ex) {
            throw new DepositException("Couldn't complete CMDI resource mapping", ex);
        }
        return true;
    }
    
}
