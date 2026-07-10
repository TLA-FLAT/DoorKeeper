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
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.ListIterator;
import net.sf.saxon.s9api.XdmItem;
import nl.mpi.tla.flat.deposit.Context;
import nl.mpi.tla.flat.deposit.DepositException;
import nl.mpi.tla.flat.deposit.sip.Resource;
import nl.mpi.tla.flat.deposit.sip.cmdi.CMDResource;
import nl.mpi.tla.util.Saxon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Store the per-resource FITS output (technical metadata) written by the FITS
 * action as a TECHMD datastream on each resource's Fedora object.
 *
 * <p>Following the OBJ datastream model, the FITS report is copied next to the
 * resource's persisted file (named {@code <resource-file>.fits.xml}) and
 * registered in Fedora as external content (a proxy), so the bytes live in the
 * persistent store on disk rather than inside Fedora.</p>
 *
 * <p>This action must therefore run <em>after</em> {@code Persist} (so
 * {@link Resource#getFile()} is the final persistent location) and <em>after</em>
 * {@code FedoraInteract} (so the resource object exists), and for atomicity
 * within the same Fedora transaction (between startTransaction and
 * commitTransaction). The copy into the persistent store is undone on rollback.</p>
 *
 * @author menzowi
 */
public class FedoraTechMD extends FedoraAction {

    private static final Logger logger = LoggerFactory.getLogger(FedoraTechMD.class.getName());

    @Override
    public boolean perform(Context context) throws DepositException {
        connect(context);

        File dir = new File(getParameter("dir", "./fits"));
        String ext = getParameter("ext", ".FITS.xml");
        String suffix = getParameter("suffix", ".fits.xml");
        String mime = getParameter("mimetype", "text/xml");
        String dsid = getParameter("dsid", "TECHMD");

        if (!dir.isDirectory())
            throw new DepositException("The FITS directory[" + dir + "] doesn't exist!");

        int stored = 0;
        for (Resource resource : context.getSIP().getResources()) {
            if (resource.getStatus() != Resource.Status.INSERT && resource.getStatus() != Resource.Status.UPDATE) {
                logger.debug("resource[" + resource.getURI() + "] status[" + resource.getStatus() + "]; skipping " + dsid);
                continue;
            }
            if (!resource.hasFile()) {
                logger.debug("resource[" + resource.getURI() + "] has no file; skipping " + dsid);
                continue;
            }
            if (!resource.hasFID()) {
                logger.warn("resource[" + resource.getURI() + "] has no Fedora PID; skipping " + dsid);
                continue;
            }
            File report = fitsFile(dir, resource, ext);
            if (!report.isFile()) {
                logger.warn("no FITS output[" + report + "] for resource[" + resource.getURI() + "]; skipping " + dsid);
                continue;
            }
            // persist the report next to the resource, named after the resource file
            File persisted = new File(resource.getFile().getParentFile(), resource.getFile().getName() + suffix);
            try {
                context.registerRollbackEvent(this, "cp", "dst", persisted.toPath().toString());
                Files.copy(report.toPath(), persisted.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new DepositException("Couldn't persist FITS output[" + report + "] to [" + persisted + "]!", e);
            }
            String fid = resource.getFID(true).toString();
            logger.debug("storing " + dsid + " for resource[" + resource.getURI() + "] -> fid[" + fid + "] from [" + persisted + "]");
            putExternal(context, fid, dsid, persisted.toURI().toString(), mime);
            stored++;
        }
        logger.info("stored " + dsid + " for [" + stored + "] resource(s)");
        return true;
    }

    /**
     * Locate the FITS output file for a resource, mirroring the naming used by
     * the FITS action: the CMD resource ID for a {@link CMDResource}, otherwise
     * the file path with every non-alphanumeric character replaced by '_'.
     */
    protected File fitsFile(File dir, Resource resource, String ext) throws DepositException {
        String name = resource.getFile().getPath().replaceAll("[^a-zA-Z0-9\\-]", "_");
        if (resource instanceof CMDResource)
            name = ((CMDResource) resource).getID();
        return new File(dir, name + ext);
    }

    @Override
    public void rollback(Context context, List<XdmItem> events) {
        for (ListIterator<XdmItem> iter = events.listIterator(events.size()); iter.hasPrevious();) {
            XdmItem event = iter.previous();
            try {
                if (Saxon.xpath2string(event, "@type").equals("cp")) {
                    File dst = new File(Saxon.xpath2string(event, "param[@name='dst']/@value"));
                    if (Files.deleteIfExists(dst.toPath()))
                        logger.debug("rollback action[" + this.getName() + "] event[cp] removed [" + dst + "]");
                }
            } catch (Exception ex) {
                logger.error("rollback action[" + this.getName() + "] event[" + event + "] failed!", ex);
            }
        }
    }
}
