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

import static com.yourmediashelf.fedora.client.FedoraClient.riSearch;
import com.yourmediashelf.fedora.client.response.RiSearchResponse;
import nl.mpi.tla.flat.deposit.Context;
import nl.mpi.tla.flat.deposit.DepositException;
import nl.mpi.tla.flat.deposit.action.mapping.util.FileWalker;
import nl.mpi.tla.flat.deposit.sip.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import java.util.Set;
import javax.xml.transform.stream.StreamSource;
import net.sf.saxon.s9api.XdmAtomicValue;
import net.sf.saxon.s9api.XdmNode;
import net.sf.saxon.s9api.XdmSequenceIterator;
import nl.mpi.tla.flat.deposit.sip.SIPInterface;
import nl.mpi.tla.flat.deposit.util.Saxon;

/**
 * This action validates whether resources as specified in the record.cmdi file match up with available files in the resource folder of a sip
 */
public class ResourceMapping extends FedoraAction {

    private static final Logger logger = LoggerFactory.getLogger(ResourceMapping.class.getName());

    @Override
    public boolean perform(Context context) throws DepositException {

        try {
            
            // connect to Fedora
            connect(context);
            
            SIPInterface sip = context.getSIP();
            Set<Resource> resources = context.getSIP().getResources();
            
            List<Path> dirs = new ArrayList<>();
            for (XdmSequenceIterator iter=(params.containsKey("dir")?params.get("dir"):new XdmAtomicValue("./resources")).iterator();iter.hasNext();)
                dirs.add(Paths.get(iter.next().getStringValue()).toFile().getAbsoluteFile().toPath());
            
            // first dir is expected to be the resources dir within the workspace
            Path dir = dirs.get(0);
            if (!dir.toFile().exists())
                logger.debug("resources dir["+dir+"] doesn't exist!");
            else if (!dir.toFile().isDirectory())
                logger.debug("resources dir["+dir+"] isn't a directory!");
            else if (!dir.toFile().canRead())
                logger.debug("resources dir["+dir+"] can't be read!");
            
            if (sip.isInsert()) {                
                // check if resources are locally available, and in a dir
                for (Resource res:resources) {
                    if (!res.hasFile())
                       throw new DepositException("Resource with URI[" + res.getURI() + "] is not available on the server!");
                    checkLocalResource(res,dirs);
                }
            } else if (sip.isUpdate()) {
            
                // check which resources are locally available, and in a dir
                // check which resources have a PID and are available in the repository
                // set status:
                // * insert   (cmdi, local, but not in repository)
                // + update   (cmdi, local and repository)
                // - - update (checksums differ)
                // - - noop   (checksums are equal)
                // + noop     (cmdi, not local, but in repository)
                // - delete   (not cmdi, not local, but in repository)
                
                for (Resource res:resources) {
                    boolean lcl = false;
                    if (res.hasFile())
                        lcl = checkLocalResource(res,dirs);
                    URI fid = null;
                    if (res.hasPID()) {
                        fid = lookupIdentifier(res.getPID());
                    }
                    if (!lcl && fid==null) {
                        throw new DepositException("This Resource["+res.getPID()+"] is not accessible!");
                    }
                    if (fid!=null) {
                        res.setFID(fid);
                        if (lcl) {
                            res.setStatus(Resource.Status.UPDATE);
                            logger.debug("This Resource["+res.getPID()+"]["+res.getFile()+"] is an update of stored Resource["+fid+"]!");
                        } else {
                            res.setStatus(Resource.Status.NOOP);
                            logger.debug("This Resource["+res.getPID()+"] is a noop of stored Resource["+fid+"]!");
                        }
                    }
                }
            }
            
            // check if too many resources are available            
            FileWalker fileWalker = new FileWalker(resources);
            Files.walkFileTree(dir, fileWalker);
            if (fileWalker.foundResources.size() > fileWalker.matchingResources.size()) {
                throw new Exception("Doorkeeper has found more files in resource directory [" + dir + "] than resources are specified in record.cmdi");
                //TODO Maybe add feature to add missing resource specification to CMDI
            }
            logger.info("Record.cmdi resources neatly match up with available files in resource folder");
            
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
