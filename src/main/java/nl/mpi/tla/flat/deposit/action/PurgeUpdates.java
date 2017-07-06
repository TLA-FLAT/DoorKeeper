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
            
            Map<String,String> nss = new HashMap<String,String>();
            nss.put("fits","http://hul.harvard.edu/ois/xml/ns/fits/fits_output");
            nss.put("dc","http://purl.org/dc/elements/1.1/");
            
            Path fits = Paths.get(getParameter("fits","./fits")).toAbsolutePath();
            if (!fits.toFile().exists())
                throw new DepositException("FITS directory["+fits+"] doesn't exist!");
            if (!fits.toFile().isDirectory())
                throw new DepositException("FITS directory["+fits+"] isn't a directory!");
            if (!fits.toFile().canRead())
                throw new DepositException("FITS directory["+fits+"] can't be read!");
            
            SIPInterface sip = context.getSIP();
            Set<Resource> resources = context.getSIP().getResources();
            
            for (Resource res:resources) {
                if (res.isUpdate()) {
                    if (!res.hasFID())
                        throw new DepositException("Update of Resource["+res.getURI()+"] but location in the Repository is unknown!");
                    
                    // get local checksum (from FITS)
                    String name = res.getFile().getPath().replaceAll("[^a-zA-Z0-9\\-]", "_");
                    if (res instanceof CMDResource)
                        name = ((CMDResource)res).getID();
                    File f = fits.resolve("./"+name+".FITS.xml").toFile();
                    logger.debug("FITS file["+f.getAbsolutePath()+"] for Resource["+res.getURI()+"]");
                    if (!f.exists())
                        throw new DepositException("FITS file for Resource["+res.getURI()+"] doesn't exist!");
                    if (!f.isFile())
                        throw new DepositException("FITS file for Resource["+res.getURI()+"] isn't a file!");
                    if (!f.canRead())
                        throw new DepositException("FITS file for Resource["+res.getURI()+"] can't be read!");
                    
                    XdmNode fd = Saxon.buildDocument(new StreamSource(f));
                    String fitsChecksum = Saxon.xpath2string(fd, "normalize-space(//fits:md5checksum)",null,nss);
                    
                    if (fitsChecksum.isEmpty())
                        throw new DepositException("FITS checksum for Resource["+res.getURI()+"] is unknown!");
                    
                    logger.debug("FITS checksum["+fitsChecksum+"] for Resource["+res.getURI()+"]");
                    
                    // get repository checksum (from FC DO DC)                    
                    FedoraResponse resp = getDatastreamDissemination(res.getFID().toString(),"DC").execute();
                    if (resp.getStatus()!=200)
                        throw new DepositException("Unexpected status["+resp.getStatus()+"] while querying Fedora Commons!");
                        
                    XdmNode fc = Saxon.buildDocument(new StreamSource(resp.getEntityInputStream()));
                    logger.debug("DC["+fc.toString()+"]");
                    String fcChecksum = Saxon.xpath2string(fc, "normalize-space(//dc:identifier[starts-with(.,'md5:')])",null,nss).replace("md5:","");

                    if (fcChecksum.isEmpty())
                        throw new DepositException("Stored checksum for Resource["+res.getURI()+"] is unknown!");
                    
                    logger.debug("FC checksum["+fcChecksum+"] for Resource["+res.getURI()+"]");

                    if (fcChecksum.equals(fitsChecksum)) {
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
    
    protected boolean checkLocalResource(Resource res,List<Path> dirs) throws DepositException {
        File f = res.getFile();
        Path p = f.getAbsoluteFile().toPath();
        boolean ok = false;
        for (Path d:dirs) {
            if (ok = p.startsWith(d))
                break;
        }
        if (!ok)
            throw new DepositException("Resource with URI[" + res.getURI() + "] is not present in a resource directory!");
        if (!f.exists()) {
            throw new DepositException("File with URI[" + res.getURI() + "] is not present in resource directory!");
        } else if (!f.canRead()) {
            throw new DepositException("File with URI[" + res.getURI() + "] is not readable!");
        }
        return true;
    }
}
