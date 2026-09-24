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

import org.fcrepo.client.*;
import java.io.File;
import java.io.FilenameFilter;
import java.io.FileInputStream;
import java.net.URI;
import java.util.Date;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javax.xml.transform.stream.StreamSource;
import net.sf.saxon.s9api.XdmItem;
import net.sf.saxon.s9api.XdmNode;
import nl.mpi.tla.flat.deposit.Context;
import nl.mpi.tla.flat.deposit.DepositException;
import static nl.mpi.tla.flat.deposit.util.Global.NAMESPACES;
import nl.mpi.tla.util.Saxon;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.filefilter.RegexFileFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author menzowi
 * @author pavsri
 */
public class FedoraInteract extends FedoraAction {

	private static final Logger logger = LoggerFactory.getLogger(FedoraInteract.class.getName());

	@Override
	public boolean perform(Context context) throws DepositException {
		try {
			connect(context);

			File dir = new File(this.getParameter("dir", "./fox"));
			String fidFilePattern = configuredFidFilePattern(context);

			// <fid>.xml (full FOXML -> create the object container + its datastreams)
			File[] foxs = dir.listFiles(((FilenameFilter) new RegexFileFilter(fidFilePattern + "\\.xml")));
			for (File fox : foxs) {
				String fid = fox.getName().replace(".xml", "").replace("_CMD","");
				logger.debug("FOXML[" + fox + "] -> [" + fid + "]");
				ingest(context, fox, fid);
			}

			// - <fid>.<asof>.props (props -> modify (some) properties)
            File[] propfiles = dir.listFiles(((FilenameFilter) new RegexFileFilter(fidFilePattern + "\\.[0-9]+\\.props")));
			for (File propfile : propfiles) {
				String fid = propfile.getName().replaceFirst("\\..*$", "").replace("_CMD", "");
				try {
					String epoch = propfile.getName().replaceFirst("^.*\\.([0-9]+)\\.props$", "$1");
					Date asof = new Date(Long.parseLong(epoch));
					logger.debug("Properties[" +  propfile + "] -> [" + fid + "][" + epoch + "=" + asof + "]");
					XdmNode ds = fcrepo(new URI(fid),transURI(context));
					XdmNode props = Saxon.buildDocument(new StreamSource(propfile));
					Map<String, List<XdmItem>> updates = new LinkedHashMap<>();
					collectObjectProperties(props, updates);
					patchProperties(context, ds, fid, updates);
				} catch (Exception e) {
                                        throw new DepositException("Unexpected response[" + e + "] while querying Fedora Commons!", e);
				}

			}

			// - <fid>.<dsid>.<asof>.file ... (DS -> modifyDatastream.dsLocation)
			// - <fid>.<dsid>.<asof>.<ext>... (DS -> modifyDatastream.content)
			foxs = dir.listFiles(((FilenameFilter) new RegexFileFilter(fidFilePattern + "\\.[A-Z][A-Z0-9\\-]*\\.[0-9]+\\.[A-Za-z0-9_]+")));
			for (File fox : foxs) {
				String fid = fox.getName().replaceFirst("\\..*$", "").replace("_CMD", "");
				String ds = fox.getName().replaceFirst("^.*\\.([A-Z][A-Z0-9\\-]*)\\..*$", "$1");
				String epoch = fox.getName().replaceFirst("^.*\\.([0-9]+)\\..*$", "$1");
				Date asof = new Date(Long.parseLong(epoch));
				String ext = fox.getName().replaceFirst("^.*\\.(.*)$", "$1");
				logger.debug("DSID[" + fox + "] -> [" + fid + "][" + ds + "][" + epoch + "=" + asof + "][" + ext + "]");
				upsertDatastream(context, fox, fid, ds, ext);
			}
		} catch (Exception e) {
			throw new DepositException("The actual deposit in Fedora failed!", e);
		}
		return true;
	}

        /** Build the accepted Fedora object filename prefixes from the workflow configuration. */
        protected String configuredFidFilePattern(Context context) throws DepositException {
            List<String> namespaces = new ArrayList<>();
            for (XdmItem namespace : context.getProperty("fedoraNamespace", "")) {
                String value = namespace.getStringValue().trim();
                if (!value.isEmpty())
                    namespaces.add(Pattern.quote(value));
            }
            if (namespaces.isEmpty())
                throw new DepositException("No Fedora namespaces are configured in fedoraNamespace");
            return "(?:" + String.join("|", namespaces) + ")_[A-Za-z0-9_]+";
        }

        protected String toSPARQL_URI(String val) {
            if (val.startsWith("http:") || val.startsWith("https:") || val.startsWith("info:fedora/")) {
                val = "<"+val+">";
            } else {
                val = "'"+val.replace("'", "\\'")+"'";
            }
            return val;
        }

        /** Build one RDF update from the last value set for each property. */
        protected String buildPropertyPatch(XdmNode current, Map<String, List<XdmItem>> updates) throws Exception {
            String nl = System.lineSeparator();
            StringBuilder del = new StringBuilder();
            StringBuilder ins = new StringBuilder();
            for (Map.Entry<String, List<XdmItem>> update : updates.entrySet()) {
                String prop = update.getKey();
                List<XdmItem> oldvals = Saxon.xpathList(current,
                    "//*[concat(namespace-uri(),local-name())='"+prop+"']/(.,@rdf:resource)[normalize-space(.)!='']",
                    null, NAMESPACES);
                for (XdmItem oldval : oldvals)
                    del.append("  <> <").append(prop).append("> ").append(toSPARQL_URI(oldval.getStringValue())).append(" .").append(nl);
                for (XdmItem newval : update.getValue())
                    ins.append("  <> <").append(prop).append("> ").append(toSPARQL_URI(newval.getStringValue())).append(" .").append(nl);
            }
            if (del.length() == 0 && ins.length() == 0)
                return null;
            StringBuilder sparql = new StringBuilder();
            if (del.length() != 0)
                sparql.append("DELETE {").append(nl).append(del).append("}").append(nl);
            if (ins.length() != 0)
                sparql.append("INSERT {").append(nl).append(ins).append("}").append(nl);
            return sparql.append("WHERE  {}").append(nl).toString();
        }

        protected void patchProperties(Context context, XdmNode current, String fid,
                Map<String, List<XdmItem>> updates) throws DepositException {
            try {
                String sparql = buildPropertyPatch(current, updates);
                if (sparql == null)
                    return;
                String rfid = fedoraConfig.getString("localServer")+"/"+fid;
                PatchBuilder pb = new PatchBuilder(new URI(rfid), fedoraClient);
                URI tx = transURI(context);
                if (tx != null) pb = pb.addTransaction(tx);
                try (FcrepoResponse response = pb.body(IOUtils.toInputStream(sparql)).perform()) {
                    if (response.getStatusCode() >= 300)
                        throw new DepositException("can't update properties of ["+rfid+"], status["+response.getStatusCode()+"]");
                }
            } catch (DepositException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new DepositException(ex);
            }
        }
        protected void upsertDatastream(Context context, File fox, String fid, String ds, String ext) throws DepositException {
            // PUT creates or replaces a binary child, and RDF updates use the
            // same operation either way. A preliminary HEAD adds no information.
            updateDatastream(context, fox, fid, ds, ext);
        }

        void updateDatastream(Context context, File fox, String fid, String ds, String ext) throws DepositException {
            logger.debug("CHECK: updateDatastream["+fid+"]["+ds+"]");
            try {
                XdmNode doc = Saxon.buildDocument(new StreamSource(fox));
                switch (ds) {
                    case "DC" -> {
                        putBinary(context, fid, "DC", new FileInputStream(fox), "text/xml");
                        applyDC(context, doc, fid);
                        rememberTitle(context, fid, doc);
                    }
                    case "OLAC" -> putBinary(context, fid, "OLAC", new FileInputStream(fox), "text/xml");
                    case "RELS-EXT" -> applyRELS(context, doc, fid);
                    case "CMD" -> putBinary(context, fid, "CMD", new FileInputStream(fox), "application/x-cmdi+xml");
                    case "OBJ" -> {
                        String content = Saxon.xpath2string(doc,"/foxml:datastreamVersion/foxml:contentLocation[1]/@REF",null,NAMESPACES);
                        String mime = Saxon.xpath2string(doc,"/foxml:datastreamVersion/@MIMETYPE",null,NAMESPACES);
                        putExternal(context, fid, "OBJ", content, mime);
                    }
                    // TN -> external location update (not yet needed)
                    default -> logger.warn("updateDatastream["+fid+"]["+ds+"] skipped: no handler for this datastream");
                }
            } catch (DepositException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new DepositException(ex);
            }
        }

        /** Fold the Dublin Core elements of {@code doc} into the object's RDF. */
        protected void applyDC(Context context, XdmNode doc, String fid) throws DepositException {
            logger.debug("DO: DC["+fid+"]");
            try {
                Map<String, List<XdmItem>> updates = new LinkedHashMap<>();
                collectDC(doc, updates);
                if (!updates.isEmpty())
                    patchProperties(context, fcrepo(new URI(fid), transURI(context)), fid, updates);
            } catch (Exception ex) {
                throw new DepositException(ex);
            }
        }

        protected void collectDC(XdmNode doc, Map<String, List<XdmItem>> updates) throws Exception {
            for (Iterator<XdmItem> iter = Saxon.xpathIterator(doc, "distinct-values(//dc:*/concat(namespace-uri(),local-name()))", null, NAMESPACES); iter.hasNext();) {
                String name = iter.next().getStringValue();
                List<XdmItem> vals = Saxon.xpathList(doc, "//dc:*[concat(namespace-uri(),local-name())='"+name+"']", null, NAMESPACES);
                updates.put(name, vals);
            }
        }

        /** Fold the RELS-EXT relations of {@code doc} into the object's RDF. */
        protected void applyRELS(Context context, XdmNode doc, String fid) throws DepositException {
            logger.debug("DO: RELS-EXT["+fid+"]");
            try {
                Map<String, List<XdmItem>> updates = new LinkedHashMap<>();
                collectRELS(doc, updates);
                if (!updates.isEmpty())
                    patchProperties(context, fcrepo(new URI(fid), transURI(context)), fid, updates);
            } catch (Exception ex) {
                throw new DepositException(ex);
            }
        }

        protected void collectRELS(XdmNode doc, Map<String, List<XdmItem>> updates) throws Exception {
            String[] prefixes = {"relsext","model","onto-relsext","oai"};
            for (String prefix : prefixes) {
                for (Iterator<XdmItem> iter = Saxon.xpathIterator(doc,
                        "distinct-values((//"+prefix+":*)[exists((.,@rdf:resource)[normalize-space(.)!=''])]/concat(namespace-uri(),local-name()))",
                        null, NAMESPACES); iter.hasNext();) {
                    String name = iter.next().getStringValue();
                    List<XdmItem> vals = Saxon.xpathList(doc,
                        "//"+prefix+":*[concat(namespace-uri(),local-name())='"+name+"']/((.,@rdf:resource)[normalize-space(.)!=''][1])",
                        null, NAMESPACES);
                    updates.put(name, vals);
                }
            }
        }

        /** Create the object container at {@code localServer/<fid>}, joining the active transaction. */
        protected void createObject(Context context, String fid) throws DepositException {
            try {
                URI tx = transURI(context);
                if (Boolean.TRUE.equals(fcrepo_exists(new URI(fid),tx))) {
                    logger.debug("CREATE skipped, object["+fid+"] already exists");
                    return;
                }
                String rfid = fedoraConfig.getString("localServer")+"/"+fid;
                logger.debug("CREATE object["+rfid+"]");
                PutBuilder pb = new PutBuilder(new URI(rfid),fedoraClient);
                if (tx != null) pb = pb.addTransaction(tx);
                try (FcrepoResponse response = pb.perform()) {
                    logger.debug("FCREPO code["+response.getStatusCode()+"]");
                    if (response.getStatusCode() >= 300)
                        throw new DepositException("can't create the object ["+rfid+"], status["+response.getStatusCode()+"]");
                }
            } catch (DepositException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new DepositException(ex);
            }
        }

        /** Apply every {@code foxml:property} found in {@code props} to the object's RDF. */
        protected void collectObjectProperties(XdmNode props, Map<String, List<XdmItem>> updates) throws Exception {
            for (Iterator<XdmItem> iter = Saxon.xpathIterator(props, "distinct-values(//foxml:property/@NAME)", null, NAMESPACES); iter.hasNext();) {
                String name = iter.next().getStringValue();
                List<XdmItem> vals = Saxon.xpathList(props, "//foxml:property[@NAME='"+name+"']/@VALUE", null, NAMESPACES);
                updates.put(name, vals);
            }
        }

        /** Keep the title actually written to DC for DrupalSync later in this flow. */
        protected void rememberTitle(Context context, String fid, XdmNode dc) throws Exception {
            String title = Saxon.xpath2string(dc, "normalize-space((//dc:title)[1])", null, NAMESPACES);
            if (title != null && !title.isBlank())
                context.putInMemory(titleMemoryKey(fid), title);
        }

        /** Create a new object from a full FOXML document: container + properties + datastreams. */
        protected void ingest(Context context, File fox, String fid) throws DepositException {
            logger.debug("INGEST["+fox+"] -> ["+fid+"]");
            try {
                URI tx = transURI(context);
                // 1. the object container
                createObject(context, fid);
                XdmNode foxml = Saxon.buildDocument(new StreamSource(fox));
                // 2. object properties + DC + RELS-EXT folded into the object's RDF
                XdmNode rdf = fcrepo(new URI(fid),tx);
                Map<String, List<XdmItem>> updates = new LinkedHashMap<>();
                collectObjectProperties(foxml, updates);
                collectDC(foxml, updates);
                collectRELS(foxml, updates);
                patchProperties(context, rdf, fid, updates);
                // 3. the binary datastreams
                if (Saxon.xpath2boolean(foxml, "exists(//foxml:datastream[@ID='CMD']//foxml:xmlContent/*)", null, NAMESPACES)) {
                    XdmNode cmd = (XdmNode) Saxon.xpath(foxml, "//foxml:datastream[@ID='CMD']//foxml:xmlContent/*", null, NAMESPACES).itemAt(0);
                    putBinary(context, fid, "CMD", IOUtils.toInputStream(cmd.toString(), "UTF-8"), "application/x-cmdi+xml");
                }
                if (Saxon.xpath2boolean(foxml, "exists(//foxml:datastream[@ID='DC']//foxml:xmlContent/*)", null, NAMESPACES)) {
                    XdmNode dc = (XdmNode) Saxon.xpath(foxml, "//foxml:datastream[@ID='DC']//foxml:xmlContent/*", null, NAMESPACES).itemAt(0);
                    String dcMime = Saxon.xpath2string(foxml, "//foxml:datastream[@ID='DC']//foxml:datastreamVersion[1]/@MIMETYPE", null, NAMESPACES);
                    putBinary(context, fid, "DC", IOUtils.toInputStream(dc.toString(), "UTF-8"), dcMime.isEmpty() ? "text/xml" : dcMime);
                    rememberTitle(context, fid, dc);
                }
                if (Saxon.xpath2boolean(foxml, "exists(//foxml:datastream[@ID='OLAC']//foxml:xmlContent/*)", null, NAMESPACES)) {
                    XdmNode olac = (XdmNode) Saxon.xpath(foxml, "//foxml:datastream[@ID='OLAC']//foxml:xmlContent/*", null, NAMESPACES).itemAt(0);
                    String olacMime = Saxon.xpath2string(foxml, "//foxml:datastream[@ID='OLAC']//foxml:datastreamVersion[1]/@MIMETYPE", null, NAMESPACES);
                    putBinary(context, fid, "OLAC", IOUtils.toInputStream(olac.toString(), "UTF-8"), olacMime.isEmpty() ? "text/xml" : olacMime);
                }
                if (Saxon.xpath2boolean(foxml, "exists(//foxml:datastream[@ID='OBJ']//foxml:contentLocation/@REF)", null, NAMESPACES)) {
                    String ref = Saxon.xpath2string(foxml, "//foxml:datastream[@ID='OBJ']//foxml:contentLocation[1]/@REF", null, NAMESPACES);
                    String mime = Saxon.xpath2string(foxml, "//foxml:datastream[@ID='OBJ']//foxml:datastreamVersion[1]/@MIMETYPE", null, NAMESPACES);
                    putExternal(context, fid, "OBJ", ref, mime);
                }
                // TN -> skipped
            } catch (DepositException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new DepositException(ex);
            }
        }

}
