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
import java.util.List;
import java.util.regex.Pattern;
import javax.xml.transform.stream.StreamSource;
import net.sf.saxon.s9api.XdmAtomicValue;
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
					applyObjectProperties(context, props, ds, fid);
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
            if (val.startsWith("http:") || val.startsWith("https:")) {
                val = "<"+val+">";
            } else {
                val = "'"+val.replace("'", "\\'")+"'";
            }
            return val;
        }

        protected void upsertProperty(Context context, String fid, String prop, String val)  throws DepositException {
            upsertProperty(context,fid,prop,List.<XdmItem>of(new XdmAtomicValue(val)));
        }

        protected void upsertProperty(Context context, String fid, String prop, List<XdmItem> vals)  throws DepositException {
            try {
                upsertProperty(context,fcrepo(new URI(fid),transURI(context)),fid,prop,vals);
            } catch (Exception ex) {
                throw new DepositException(ex);
            }
        }

        protected void upsertProperty(Context context, XdmNode ds, String fid, String prop, List<XdmItem> vals)  throws DepositException {
            try {
                if (prop.equals("http://purl.org/dc/elements/1.1/identifier"))
                    upsertIdentifier(context,ds,fid,vals);
                String rest = fedoraConfig.getString("localServer");
                String rfid = rest+"/"+fid;
                List<XdmItem> oldvals = Saxon.xpathList(ds,"//*[concat(namespace-uri(),local-name())='"+prop+"']/(.,@rdf:resource)[normalize-space(.)!='']",null,NAMESPACES);
                String NL = System.lineSeparator();
                // <> is the patched resource itself, so the update also works on a
                // freshly created object without any properties yet; a SPARQL update
                // allows only one DELETE and one INSERT block, so gather all values
                // in a single block each
                String del = "";
                for (XdmItem oldval:oldvals) {
                    String old = toSPARQL_URI(oldval.getStringValue());
                    del += "  <> <"+prop+"> "+old+" ."+NL;
                }
                String ins = "";
                for (XdmItem newval:vals) {
                    String val = toSPARQL_URI(newval.getStringValue());
                    ins += "  <> <"+prop+"> "+val+" ."+NL;
                }
                if (del.isEmpty() && ins.isEmpty()) {
                    logger.debug("SKIP: rfid[" +rfid + "] prop["+prop+"] no values");
                    return;
                }
                String sparql = "";
                if (!del.isEmpty())
                    sparql += "DELETE {"+NL+del+"}"+NL;
                if (!ins.isEmpty())
                    sparql += "INSERT {"+NL+ins+"}"+NL;
                sparql += "WHERE  {}"+NL;
                logger.debug("upsert rfid[" +rfid + "] prop["+prop+"]");
                PatchBuilder pb = new PatchBuilder(new URI(rfid),fedoraClient);
                URI tx = transURI(context);
                if (tx != null) pb = pb.addTransaction(tx);
                try (FcrepoResponse response = pb.body(IOUtils.toInputStream(sparql)).perform()) {
                    logger.debug("FCREPO code["+response.getStatusCode()+"]");
                    if (response.getStatusCode() >= 300)
                        throw new DepositException("can't update property["+prop+"] of ["+rfid+"], status["+response.getStatusCode()+"]");
                }
             } catch (DepositException ex) {
                throw ex;
             } catch (Exception ex) {
                throw new DepositException(ex);
            }
        }

        protected void upsertIdentifier(Context context, XdmNode ds, String fid, List<XdmItem> vals)  throws DepositException {
            try {
                String rest = fedoraConfig.getString("localServer");
                String rfid = rest+"/"+fid;
                for (XdmItem val:vals) {
                    if (val.getStringValue().startsWith("md5:")) {
                        String oldval = Saxon.xpath2string(ds,"//*[concat(namespace-uri(),local-name())='http://purl.org/dc/elements/1.1/identifier'][starts-with(.,'md5:')]");
                        if (oldval.strip().equals("")) {
                            // insert
                           String sparql_template= """
                             INSERT { <> <http://purl.org/dc/elements/1.1/identifier> '%s' }
                             WHERE  {}""".indent(2);
                            String sparql=sparql_template.formatted(val.getStringValue());
                            logger.debug("insert identifier rfid[" +rfid + "] val["+val+"]");
                            PatchBuilder pb = new PatchBuilder(new URI(rfid),fedoraClient);
                            URI tx = transURI(context);
                            if (tx != null) pb = pb.addTransaction(tx);
                            try (FcrepoResponse response = pb.body(IOUtils.toInputStream(sparql)).perform()) {
                                if (response.getStatusCode() >= 300)
                                    throw new DepositException("can't insert identifier["+val+"] of ["+rfid+"], status["+response.getStatusCode()+"]");
                            }
                        } else if (!oldval.strip().equals(val.getStringValue().strip())) {
                            // update
                           String sparql_template= """
                             DELETE { <> <http://purl.org/dc/elements/1.1/identifier> '%s' }
                             INSERT { <> <http://purl.org/dc/elements/1.1/identifier> '%s' }
                             WHERE  {}""".indent(2);
                            String sparql=sparql_template.formatted(oldval,val.getStringValue());
                            logger.debug("update identifier rfid[" +rfid + "] old["+oldval+"] new["+val+"]");
                            PatchBuilder pb = new PatchBuilder(new URI(rfid),fedoraClient);
                            URI tx = transURI(context);
                            if (tx != null) pb = pb.addTransaction(tx);
                            try (FcrepoResponse response = pb.body(IOUtils.toInputStream(sparql)).perform()) {
                                if (response.getStatusCode() >= 300)
                                    throw new DepositException("can't update identifier["+val+"] of ["+rfid+"], status["+response.getStatusCode()+"]");
                            }
                        } else
                            logger.debug("SKIP: rfid[" +rfid + "] prop[http://purl.org/dc/elements/1.1/identifier] old["+oldval+"] new["+val+"] noop");
                    } else
                        logger.debug("SKIP: rfid[" +rfid + "] prop[http://purl.org/dc/elements/1.1/identifier] new["+val+"] not md5");
                }
            } catch (DepositException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new DepositException(ex);
            }
        }
        protected void upsertDatastream(Context context, File fox, String fid, String ds, String ext) throws DepositException {
            try {
                Boolean exists = fcrepo_exists(new URI(fid),ds,transURI(context));
                if (exists!=null && exists.booleanValue())
                    updateDatastream(context, fox, fid, ds, ext);
                else
                    insertDatastream(context, fox, fid, ds, ext);
            } catch (Exception e) {
                throw new DepositException(e);
            }
        }

        protected void insertDatastream(Context context, File fox, String fid, String ds, String ext) throws DepositException {
            // In Fedora 6 there is no separate "add datastream" verb: a PUT to the
            // datastream path creates-or-replaces the resource, and the SPARQL upsert
            // used for the RDF-backed datastreams (DC/RELS-EXT) is equally valid against
            // an object that doesn't have the property yet. So creation mirrors update.
            // DC       -> binary child (canonical XML record, e.g., for OAI-PMH)
            //             + folded into the object's RDF (e.g. dc:identifier md5:...)
            // OLAC     -> binary child (canonical XML record, e.g., for OAI-PMH)
            // RELS-EXT -> folded into the object's RDF (e.g. isConstituentOf)
            // CMD      -> new binary child
            // OBJ      -> new external-content (proxy) binary child
            // TN       -> skip
            logger.debug("DO: insertDatastream["+fid+"]["+ds+"] via update semantics");
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
                // group the values per property, so each property is upserted in one go
                for (Iterator<XdmItem> iter = Saxon.xpathIterator(doc, "distinct-values(//dc:*/concat(namespace-uri(),local-name()))", null, NAMESPACES); iter.hasNext();) {
                    String name = iter.next().getStringValue();
                    List<XdmItem> vals = Saxon.xpathList(doc, "//dc:*[concat(namespace-uri(),local-name())='"+name+"']", null, NAMESPACES);
                    logger.debug("DO: DC["+fid+"]["+name+"]["+vals.size()+" value(s)]");
                    upsertProperty(context,fid,name,vals);
                }
            } catch (Exception ex) {
                throw new DepositException(ex);
            }
        }

        /** Fold the RELS-EXT relations of {@code doc} into the object's RDF. */
        protected void applyRELS(Context context, XdmNode doc, String fid) throws DepositException {
            logger.debug("DO: RELS-EXT["+fid+"]");
            try {
                String[] prefixes = {"relsext","model","onto-relsext","oai"};
                for (String prefix:prefixes) {
                    // group the values per property, so each property is upserted in one go
                    for (Iterator<XdmItem> iter = Saxon.xpathIterator(doc, "distinct-values((//"+prefix+":*)[exists((.,@rdf:resource)[normalize-space(.)!=''])]/concat(namespace-uri(),local-name()))", null, NAMESPACES); iter.hasNext();) {
                        String name = iter.next().getStringValue();
                        List<XdmItem> vals = Saxon.xpathList(doc, "//"+prefix+":*[concat(namespace-uri(),local-name())='"+name+"']/((.,@rdf:resource)[normalize-space(.)!=''][1])", null, NAMESPACES);
                        logger.debug("DO: RELS-EXT["+fid+"]["+prefix+"]["+name+"]["+vals.size()+" value(s)]");
                        upsertProperty(context,fid,name,vals);
                    }
                }
            } catch (Exception ex) {
                throw new DepositException(ex);
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
        protected void applyObjectProperties(Context context, XdmNode props, XdmNode ds, String fid) throws DepositException {
            try {
                for (Iterator<XdmItem> iter = Saxon.xpathIterator(props, "distinct-values(//foxml:property/@NAME)", null, NAMESPACES); iter.hasNext();) {
                    XdmItem prop = iter.next();
                    String name = prop.getStringValue();
                    logger.debug("Property["+name+"]");
                    List<XdmItem> vals = Saxon.xpathList(props, "//foxml:property[@NAME='"+name+"']/@VALUE", null, NAMESPACES);
                    upsertProperty(context,ds,fid,name,vals);
                }
            } catch (Exception ex) {
                throw new DepositException(ex);
            }
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
                applyObjectProperties(context, foxml, rdf, fid);
                applyDC(context, foxml, fid);
                applyRELS(context, foxml, fid);
                // 3. the binary datastreams
                if (Saxon.xpath2boolean(foxml, "exists(//foxml:datastream[@ID='CMD']//foxml:xmlContent/*)", null, NAMESPACES)) {
                    XdmNode cmd = (XdmNode) Saxon.xpath(foxml, "//foxml:datastream[@ID='CMD']//foxml:xmlContent/*", null, NAMESPACES).itemAt(0);
                    putBinary(context, fid, "CMD", IOUtils.toInputStream(cmd.toString(), "UTF-8"), "application/x-cmdi+xml");
                }
                if (Saxon.xpath2boolean(foxml, "exists(//foxml:datastream[@ID='DC']//foxml:xmlContent/*)", null, NAMESPACES)) {
                    XdmNode dc = (XdmNode) Saxon.xpath(foxml, "//foxml:datastream[@ID='DC']//foxml:xmlContent/*", null, NAMESPACES).itemAt(0);
                    String dcMime = Saxon.xpath2string(foxml, "//foxml:datastream[@ID='DC']//foxml:datastreamVersion[1]/@MIMETYPE", null, NAMESPACES);
                    putBinary(context, fid, "DC", IOUtils.toInputStream(dc.toString(), "UTF-8"), dcMime.isEmpty() ? "text/xml" : dcMime);
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
