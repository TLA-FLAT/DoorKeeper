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
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import javax.xml.transform.stream.StreamSource;
import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.XdmAtomicValue;
import net.sf.saxon.s9api.XdmItem;
import net.sf.saxon.s9api.XdmNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import nl.mpi.tla.flat.deposit.Context;
import nl.mpi.tla.flat.deposit.DepositException;
import nl.mpi.tla.flat.deposit.sip.Resource;
import nl.mpi.tla.flat.deposit.sip.SIPInterface;
import nl.mpi.tla.flat.deposit.action.fits.util.FITSHandler;
import nl.mpi.tla.flat.deposit.sip.cmdi.CMDResource;
import static nl.mpi.tla.flat.deposit.util.Global.NAMESPACES;
import nl.mpi.tla.flat.deposit.util.Saxon;
import org.apache.commons.io.FileUtils;

/**
 *
 * @author menzowi
 */
public class FITSsimple extends AbstractAction {
	
    private static final Logger logger = LoggerFactory.getLogger(FITSsimple.class);
    
	
    @Override
    public boolean perform(Context context) throws DepositException {
    	
    	boolean allAcceptable = true;
        
        File dir = null;
        if (hasParameter("dir")) {
            dir = new File(getParameter("dir"));
            if (!dir.exists()) {
                try {
                    FileUtils.forceMkdir(dir);
                } catch (Exception ex) {
                    throw new DepositException(ex);
                }
            }
        }
    	
    	String fitsService = getParameter("fitsService");
        if (fitsService == null)
            throw new DepositException("Missing fitsService parameter!");
        if (!fitsService.endsWith("/")) {
            fitsService += "/";
        }
        URL fitsURL = null;
        try {
            fitsURL = new URL(fitsService);
        } catch (MalformedURLException ex) {
            throw new DepositException(ex);
        }
        
    	String mimetypesFileLocation = getParameter("mimetypes");
        if (mimetypesFileLocation == null)
            throw new DepositException("Missing mimetypes parameter!");
        
        XdmNode mimetypes = null;
        try {
            mimetypes = Saxon.buildDocument(new StreamSource(mimetypesFileLocation));
        } catch (SaxonApiException ex) {
            throw new DepositException(ex);
        }
    	
    	for(Resource resource : context.getSIP().getResources()) {
            if(resource.hasFile()) {
                File file = resource.getFile();
    			
                XdmNode result;
                try {
                    URL call = new URL(fitsURL,"examine?file="+file.getAbsolutePath());
                    result = Saxon.buildDocument(new StreamSource(call.toString()));
                } catch (Exception ex) {
                    throw new DepositException(ex);
                }
                
                if (dir != null) {
                    // save the FITS report for this resource
                    String name = file.getPath().replaceAll("[^a-zA-Z0-9\\-]", "_");
                    if (resource instanceof CMDResource)
                        name = ((CMDResource)resource).getID();
                    File out = new File(dir + "/"+name+".FITS.xml");
                    try {
                        Saxon.save(result.asSource(),out);
                    } catch (SaxonApiException ex) {
                        throw new DepositException(ex);
                    }
                }
    			
                try {
                    String xp = Saxon.xpath2string(mimetypes,"normalize-space(/mimetypes/@xpath)");
                    if (xp.equals(""))
                        xp = "distinct-values(/fits:fits/fits:identification/fits:identity/@mimetype)";
                    List<XdmItem> mimes = Saxon.xpathList(result,xp,null,NAMESPACES);
                    logger.debug("FITS has detected {} mimetypes for resource[{}]:",mimes.size(),file);
                    for (XdmItem m:mimes)
                        logger.debug("- '{}'",m.getStringValue());
                    if(mimes.size()==0) {
                        logger.error("No mimetype found for resource[{}]",file);
                        String fallback = Saxon.xpath2string(mimetypes,"normalize-space(/mimetypes/@fallback)");
                        if (!fallback.equals("")) {
                            logger.error("Use fallback mimetype[{}] found for resource[{}]",fallback,file);
                            if(resource.hasMime() && !resource.getMime().equals(fallback)) {
                                logger.warn("Resource mimetype changed from '{}' to '{}'",resource.getMime(),fallback);
                            }
                            logger.debug("Setting resource mimetype to '{}'", fallback);
                            resource.setMime(fallback);
                        } else {
                            allAcceptable = false;
                        }
                        continue;
                    }
                    if(mimes.size() > 1) {
                        logger.error("More than one mimetype for resource[{}]",file);
                        allAcceptable = false;
                        continue;
                    }
                    String mime = mimes.get(0).getStringValue();
                    
                    Map vars = new HashMap();
                    vars.put("mime",new XdmAtomicValue(mime));
                    XdmItem mimetype = Saxon.xpathSingle(mimetypes,"/mimetypes/mimetype[@value=$mime]",vars);
                    if (mimetype == null) {
                        logger.error("Resource[{}] has a mimetype which is NOT ALLOWED in this repository: '{}'!",file,mime);
                        allAcceptable = false;
                        continue;
                    }
                    // TODO: evaluate additional mimetype/check/@xpath 
                    // TODO: use Saxon.xpathIterator(mimetype,"check/@xpath",null,NAMESPACES) to loop over the check XPaths
                    // TODO: evaluate each check XPath using Saxon.xpath2boolean(result,<check XPath>,null,NAMESPACES)
                    // TODO: if one fails the type is NOT ALLOWED, tell the user why not
                    // TODO: if all succeeds the mimetype is ALLOWED
                    logger.info("Resource[{}] has a mimetype which is ALLOWED in this repository: '{}'",file,mime);
                    
                    if(resource.hasMime() && !resource.getMime().equals(mime)) {
                        logger.warn("Resource mimetype changed from '{}' to '{}'",resource.getMime(),mime);
                    }
                    logger.debug("Setting resource mimetype to '{}'", mime);
                    resource.setMime(mime);

                } catch (Exception ex) {
                    throw new DepositException(ex);
                }
            }
    	}
    	return allAcceptable;
    }
}
