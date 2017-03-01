package nl.mpi.tla.flat.deposit.action;

import nl.mpi.tla.flat.deposit.Context;
import nl.mpi.tla.flat.deposit.DepositException;
import nl.mpi.tla.flat.deposit.action.mapping.util.FileWalker;
import nl.mpi.tla.flat.deposit.sip.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import java.util.Set;

/**
 * This action validates whether resources as specified in the record.cmdi file match up with available files in the resource folder of a sip
 */
public class ResourceMapping extends AbstractAction{

    private static final Logger logger = LoggerFactory.getLogger(ResourceMapping.class.getName());

    @Override
    public boolean perform(Context context) throws DepositException {

        try {
            // Check for every CMDI resource (set of resources) if file is available and accessible
            Set<Resource> resources = context.getSIP().getResources();

            for (Resource res : resources) {
                File f = new File(Paths.get(res.getURI()).toString());
                if (!f.exists()) {
                    throw new Exception("File with URI " + res.getURI() + " is not present in resource directory");
                } else if (!f.canRead()) {
                    throw new Exception("File with URI " + res.getURI() + " is not readable");
                }
            }

            // Check if resource directory exists. In case no resources are specified that's okay, otherwise throw exception
            Path resourcesDir = Paths.get(getParameter("dir", "./resources"));
            if (!resourcesDir.toFile().exists()) {
                logger.debug("resources dir["+resourcesDir+"] doesn't exist!");
                if (resources.size() == 0) {
                   // Add debug info for empty resources
                    logger.debug("No resources have been specified in record.cmdi");
                } else {
                    throw new Exception("resources directory does not exist");
                }
            } else {
                    // Check in case of an existing resource directory if all files are also specified in record.cmdi
                    FileWalker fileWalker = new FileWalker(resources);
                    Files.walkFileTree(resourcesDir, fileWalker);
                    if (fileWalker.foundResources.size() > fileWalker.matchingResources.size()) {

                        throw new Exception("Doorkeeper has found more files in resource directory [" + resourcesDir + "] than resources are specified in record.cmdi");

                        //TODO Maybe add feature to add missing resource specification to CMDI
                    }

            }

            logger.info("Record.cmdi resources neatly match up with available files in resource folder");


        } catch (Exception ex) {
            throw new DepositException("Couldn't complete cmdi resource mapping", ex);
        }
        return true;

    }
}
