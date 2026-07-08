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
	private static final int EXTERNAL_CONTENT_MAX_ATTEMPTS = 121;
	private static final long EXTERNAL_CONTENT_RETRY_DELAY_MS = 250L;

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

        protected URI transURI(Context context) throws DepositException {
            try {
                if (context.hasInMemory("transLocation"))
                    return new URI(context.getFromMemory("transLocation").toString());
                logger.warn("No Fedora transaction in memory; write will not be atomic!");
                return null;
            } catch (Exception e) {
                throw new DepositException("Couldn't determine the Fedora transaction URI!", e);
            }
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
                    context.registerRollbackEvent(this, "property", "fid", fid, "prop", prop, "old", old);
                    del += "  <> <"+prop+"> "+old+" ."+NL;
                }
                String ins = "";
                for (XdmItem newval:vals) {
                    String val = toSPARQL_URI(newval.getStringValue());
                    context.registerRollbackEvent(this, "property", "fid", fid, "prop", prop, "new", val);
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
                logger.debug("rfid[" +rfid + "] prop["+prop+"] sparql["+sparql+"]");
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
                    String pre = val.getStringValue().replaceAll("(.+:).*", "$1");
                    logger.debug("MENZO: pre[" + pre + "]");
                    if (val.getStringValue().startsWith("md5:")) {
                        String oldval = Saxon.xpath2string(ds,"//*[concat(namespace-uri(),local-name())='http://purl.org/dc/elements/1.1/identifier'][starts-with(.,'md5:')]");
                        if (oldval.strip().equals("")) {
                            // insert
                           String sparql_template= """
                             INSERT { <> <http://purl.org/dc/elements/1.1/identifier> '%s' }
                             WHERE  {}""".indent(2);
                           context.registerRollbackEvent(this, "property", "fid", fid, "prop", "http://purl.org/dc/elements/1.1/identifier", "val", val.getStringValue());
                            String sparql=sparql_template.formatted(val.getStringValue());
                            logger.debug("rfid[" +rfid + "] prop[http://purl.org/dc/elements/1.1/identifier] val["+val+"] sparql["+sparql+"]");
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
                           context.registerRollbackEvent(this, "property", "fid", fid, "prop", "http://purl.org/dc/elements/1.1/identifier", "old", oldval, "new", val.getStringValue());
                            String sparql=sparql_template.formatted(oldval,val.getStringValue());
                            logger.debug("rfid[" +rfid + "] prop[http://purl.org/dc/elements/1.1/identifier] old["+oldval+"] new["+val+"] sparql["+sparql+"]");
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
                    default -> logger.debug("TODO: updateDatastream["+fid+"]["+ds+"] not yet implemented!");
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

        /** PUT a binary (LDP-NR) child datastream, joining the active transaction. */
        protected void putBinary(Context context, String fid, String ds, java.io.InputStream body, String mime) throws DepositException {
            try (body) {
                String rfid = fedoraConfig.getString("localServer")+"/"+fid+"/"+ds;
                logger.debug("PUT binary["+rfid+"]["+mime+"]");
                PutBuilder pb = new PutBuilder(new URI(rfid),fedoraClient);
                URI tx = transURI(context);
                if (tx != null) pb = pb.addTransaction(tx);
                try (FcrepoResponse response = pb.body(body, mime).perform()) {
                    logger.debug("FCREPO code["+response.getStatusCode()+"]");
                    if (response.getStatusCode() >= 300)
                        throw new DepositException("can't store the binary ["+rfid+"], status["+response.getStatusCode()+"]");
                }
            } catch (DepositException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new DepositException(ex);
            }
        }

        /** PUT an external-content (proxy) binary child datastream, joining the active transaction. */
        protected void putExternal(Context context, String fid, String ds, String ref, String mime) throws DepositException {
            try {
                String rfid = fedoraConfig.getString("localServer")+"/"+fid+"/"+ds;
                logger.debug("PUT external content["+rfid+"] set to ["+ref+"]["+mime+"]");
                URI refUri = new URI(ref);
                URI tx = transURI(context);
                for (int attempt = 1; attempt <= EXTERNAL_CONTENT_MAX_ATTEMPTS; attempt++) {
                    PutBuilder pb = new PutBuilder(new URI(rfid),fedoraClient);
                    if (tx != null) pb = pb.addTransaction(tx);
                    try (FcrepoResponse response = pb.externalContent(refUri, mime, "proxy").perform()) {
                        int status = response.getStatusCode();
                        logger.debug("FCREPO code["+status+"]");
                        if (status < 300)
                            return;

                        // On Docker Desktop a file moved into a shared bind mount can
                        // briefly be invisible in another container. Fedora reports
                        // that allowlist/existence check as HTTP 400. Retry only that
                        // narrowly defined case; all other errors remain immediate.
                        boolean retryable = status == 400
                                && "file".equalsIgnoreCase(refUri.getScheme())
                                && attempt < EXTERNAL_CONTENT_MAX_ATTEMPTS;
                        if (!retryable)
                            throw new DepositException("can't store the external content of ["+rfid+"], status["+status+"]");

                        logger.warn("External file["+ref+"] is not visible to Fedora yet; retrying "
                                + "attempt["+(attempt + 1)+"/"+EXTERNAL_CONTENT_MAX_ATTEMPTS+"]");
                    }

                    try {
                        Thread.sleep(EXTERNAL_CONTENT_RETRY_DELAY_MS);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new DepositException("Interrupted while waiting for external content ["+ref+"]", ex);
                    }
                }
            } catch (DepositException ex) {
                throw ex;
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

        //TODO: remove old methods vvvv
/*
	protected void upsertDatastream(Context context, File fox, String fid, String dsid, String ext) throws DepositException {
		try {
			// check if the DS already exists (will throw
			GetDatastreamResponse res = getDatastream(fid, dsid).execute();
			if (res.getStatus() == 200) {
				// update DS
				updateDatastream(context, fox, fid, dsid, ext);
			} else
				throw new DepositException("Unexpected status[" + res.getStatus() + "] while interacting with Fedora Commons!");
		} catch (FedoraClientException e) {
			if (e.getStatus() == 404) {
				// insert DS
				insertDatastream(context, fox, fid, dsid, ext);
			} else
				throw new DepositException("Unexpected status[" + e.getStatus() + "] while querying Fedora Commons!",e);
		} catch (DepositException ex) {
			throw ex;
		} catch (Exception ex) {
			throw new DepositException(ex);
		}
	}

	void insertDatastream(Context context, File fox, String fid, String dsid, String ext) throws DepositException {
		try {
			context.registerRollbackEvent(this, "insert", "fid", fid, "dsid", dsid);

			SIPInterface sip = context.getSIP();
			AddDatastreamResponse adsResponse = null;
			if (ext.equals("file")) {
				XdmNode f = Saxon.buildDocument(new StreamSource(fox));
				String loc = Saxon.xpath2string(f, "/foxml:datastreamVersion/foxml:contentLocation/@REF", null, NAMESPACES);
				if (loc == null)
					throw new DepositException("Resource FOX[" + fox + "] without DS location!");
				String mime = Saxon.xpath2string(f, "/foxml:datastreamVersion/@MIMETYPE", null, NAMESPACES);
				String lbl = Saxon.xpath2string(f, "/foxml:datastreamVersion/@LABEL", null, NAMESPACES);
				AddDatastream ad = addDatastream(fid, dsid).controlGroup("E").dsLocation(loc);
				if (mime != null)
					ad.mimeType(mime);
				if (lbl != null)
					ad.dsLabel(lbl);
				adsResponse = ad.logMessage("Added " + dsid).execute();
			} else {
				AddDatastream ad = addDatastream(fid, dsid);
				if (dsid.equals("CMD"))
					ad.mimeType("application/x-cmdi+xml");
				ad.content(fox);
				adsResponse = ad.logMessage("Added " + dsid).execute();
			}
			if (adsResponse.getStatus() != 201)
				throw new DepositException("Unexpected status[" + adsResponse.getStatus() + "] while interacting with Fedora Commons!");
			logger.info("Updated FedoraObject[" + fid + "][" + dsid + "][" + adsResponse.getLastModifiedDate() + "]");
			// we should update the PID asOfDateTime
			fid = completeFID(sip, new URI(fid), adsResponse.getLastModifiedDate()).toString();
			logger.debug("Should match FID[" + fid + "]");
		} catch (FedoraClientException e) {
			if (e.getStatus() == 404) {
				throw new DepositException("FedoraObject[" + fid + "] doesn't exist!", e);
			} else
				throw new DepositException("Unexpected status[" + e.getStatus() + "] while querying Fedora Commons!", e);
		} catch (Exception ex) {
			throw new DepositException(ex);
		}
	}


	void updateDatastream(Context context, File fox, String fid, String dsid, String ext) throws DepositException {
		updateDatastream(context, fox, fid, dsid, null, ext);
	}

	void updateDatastream(Context context, File fox, String fid, String dsid, Date asof, String ext)
			throws DepositException {
		try {
// TODO:			context.registerRollbackEvent(this, "update", "fid", fid, "dsid", dsid, "last", Global.asOfDateTime(getDatastream(fid, dsid).execute().getLastModifiedDate()));

			SIPInterface sip = context.getSIP();
			ModifyDatastreamResponse mdsResponse = null;
			if (ext.equals("file")) {
				XdmNode f = Saxon.buildDocument(new StreamSource(fox));
				String loc = Saxon.xpath2string(f, "/foxml:datastreamVersion/foxml:contentLocation/@REF", null, NAMESPACES);
				if (loc == null)
					throw new DepositException("Resource FOX[" + fox + "] without DS location!");
				String mime = Saxon.xpath2string(f, "/foxml:datastreamVersion/@MIMETYPE", null, NAMESPACES);
				String lbl = Saxon.xpath2string(f, "/foxml:datastreamVersion/@LABEL", null, NAMESPACES);
				ModifyDatastream md = modifyDatastream(fid, dsid).dsLocation(loc);
				if (asof != null)
					md.lastModifiedDate(asof);
				if (mime != null)
					md.mimeType(mime);
				if (lbl != null)
					md.dsLabel(lbl);
				mdsResponse = md.logMessage("Updated " + dsid).execute();
			} else {
				ModifyDatastream md = modifyDatastream(fid, dsid);
				if (asof != null)
					md.lastModifiedDate(asof);
				if (dsid.equals("CMD"))
					md.mimeType("application/x-cmdi+xml");
				md.content(fox);
				mdsResponse = md.logMessage("Updated " + dsid).execute();
			}
			if (mdsResponse.getStatus() != 200)
				throw new DepositException("Unexpected status[" + mdsResponse.getStatus() + "] while interacting with Fedora Commons!");
			logger.info("Updated FedoraObject[" + fid + "][" + dsid + "][" + mdsResponse.getLastModifiedDate() + "]");
			// we should update the PID asOfDateTime
			fid = completeFID(sip, new URI(fid), mdsResponse.getLastModifiedDate()).toString();
			logger.debug("Should match FID[" + fid + "]");
		} catch (FedoraClientException e) {
			if (e.getStatus() == 404) {
				throw new DepositException("FedoraObject[" + fid + "] and/or datastream[" + dsid + "] doesn't exist!",e);
			} else
				throw new DepositException("Unexpected status[" + e.getStatus() + "] while querying Fedora Commons!",e);
		} catch (Exception ex) {
			throw new DepositException(ex);
		}
	}

	protected URI completeFID(SIPInterface sip, URI fid, Date date) throws DepositException {
		if (sip.hasFID() && sip.getFID().toString().startsWith(fid.toString())) {
			sip.setFIDasOfTimeDate(date); // will keep the latest asOfDateTime
			logger.debug("Fedora SIP datastream[" + sip.getPID() + "]->[" + sip.getFID() + "]=[" + fid + "][" + date+ "] completed!");
			return sip.getFID();
		}
		Collection col = sip.getCollectionByFID(fid);
		if (col != null) {
			col.setFIDasOfTimeDate(date); // will keep the latest asOfDateTime
			logger.debug("Fedora Collection datastream[" + col.getPID() + "]->[" + col.getFID() + "]=[" + fid + "]["+ date + "] completed!");
			return col.getFID();
		}
		Resource res = sip.getResourceByFID(fid);
		if (res != null) {
			res.setFIDasOfTimeDate(date); // will keep the latest asOfDateTime
			logger.debug("Fedora Resource datastream[" + res.getPID() + "]->[" + res.getFID() + "]=[" + fid + "]["+ date + "] completed!");
			return res.getFID();
		}
		logger.debug("Fedora datastream[" + fid + "][" + date + "] couldn't be associated with a PID!");
		return null;
	}

	public void rollback(Context context, List<XdmItem> events) {
		if (events.size() > 0) {
			for (ListIterator<XdmItem> iter = events.listIterator(events.size()); iter.hasPrevious();) {
				XdmItem event = iter.previous();
				try {
					String tpe = Saxon.xpath2string(event, "@type");
					if (tpe.equals("ingest")) {
						String fid = Saxon.xpath2string(event, "param[@name='fid']/@value");
						if (fid != null) {
							if (getObjectProfile(fid).execute().getLastModifiedDate().equals(Global.asOfDateTime(context.getSIP().getFID().getRawFragment().replaceAll(".*@", "")))) {
								purgeObject(fid).logMessage("rollback of ingest").execute();
								logger.debug("ingest rollback for fid["+fid+"]");
							} else {
								logger.warn("couldn't rollback ingest[" + fid + "] as it has been updated already!");
							}
						}
					}
					if (tpe.equals("property")) {
						String fid = Saxon.xpath2string(event, "param[@name='fid']/@value");
						String last = Saxon.xpath2string(event, "param[@name='last']/@value");
						Date dlast = Global.asOfDateTime(last);
						if (getObjectProfile(fid).execute().getLastModifiedDate().after(dlast)) {
							String old = Saxon.xpath2string(event, "param[@name='old']/@value");
							modifyObject(fid).label(old).logMessage("rollback of label update").execute();
							logger.debug("property rollback for fid["+fid+"]");
						} else {
							logger.debug("ignoring property rollback for fid[" + fid+ "] as no changes happened");
						}
					}
					if (tpe.equals("insert")) {
						String fid = Saxon.xpath2string(event, "param[@name='fid']/@value");
						String dsid = Saxon.xpath2string(event, "param[@name='dsid']/@value");
						if (fid != null & dsid != null) {
							URI ufid = null;
							if (context.getSIP().getFID().toString().startsWith(fid)) {
								// update of a datastream in the compound
								ufid = context.getSIP().getFID();
							}
							if (ufid == null) {
								Resource res = context.getSIP().getResourceByFID(new URI(fid));
								if (res != null) {
									// update of a datastream in a resource
									ufid = res.getFID();
								}
							}
							if (ufid == null) {
								Collection col = context.getSIP().getCollectionByFID(new URI(fid));
								if (col != null) {
									// update of a datastream in a collection
									ufid = col.getFID();
								}
							}
							if (ufid != null) {
								String fragment = ufid.getRawFragment();
								if (fragment != null) {
									Date asof = Global.asOfDateTime(context.getSIP().getResourceByFID(ufid).getFID().getRawFragment().replaceAll(".*@", ""));
									Date lmod = getObjectProfile(fid).execute().getLastModifiedDate();
									if (lmod.equals(asof)) {
										purgeDatastream(fid, dsid).logMessage("rollback of insert").execute();
										logger.debug("insert rollback for fid["+ fid +"] dsid["+ dsid+"]");
									} else {
                                                                            logger.debug("ignored rollback insert[" + fid + "] [" + dsid + "] the asof out of sync (asof[" + asof + "]!=lmod[" + lmod+ "])");
                                                                        }
								} else {
									logger.warn("couldn't rollback insert[" + fid + "] [" + dsid + "] the asof[" + ufid+ "] is unknown!");
								}
							} else {
								logger.warn("couldn't rollback insert[" + fid + "] [" + dsid+ "] as the resource/collection couldn't be found!");
							}
						} else {
							logger.debug("ignoring the insert rollback for fid[" + fid + "] dsid[" + dsid+ "] as no changes happened");
						}
					}
					if (tpe.equals("update")) {
						String fid = Saxon.xpath2string(event, "param[@name='fid']/@value");
						String dsid = Saxon.xpath2string(event, "param[@name='dsid']/@value");
						String last = Saxon.xpath2string(event, "param[@name='last']/@value");
						Date dlast = Global.asOfDateTime(last);
                                                Date min = getNextDatastreamMod(fid,dsid,dlast);
						if (min!=null) {
							URI ufid = null;
							if (context.getSIP().getFID().toString().startsWith(fid)) {
								// update of a datastream in the compound
								ufid = context.getSIP().getFID();
							}
							if (ufid == null) {
								Resource res = context.getSIP().getResourceByFID(new URI(fid));
								if (res != null) {
									// update of a datastream in a resource
									ufid = res.getFID();
								}
							}
							if (ufid == null) {
								Collection col = context.getSIP().getCollectionByFID(new URI(fid));
								if (col != null) {
									// update of a datastream in a collection
									ufid = col.getFID();
								}
							}
							if (ufid != null) {
								String fragment = ufid.getRawFragment();
								if (fragment != null) {
									Date max = Global.asOfDateTime(fragment.replaceAll(".*@", ""));
									Date lmod = getDatastream(fid, dsid).execute().getLastModifiedDate();
                                                                        logger.debug("update rollback mod["+lmod+"]["+lmod.toInstant().toEpochMilli()+"] in range[min["+min+"]["+min.toInstant().toEpochMilli()+"],max["+max+"]["+max.toInstant().toEpochMilli()+"]]?["+((lmod.equals(min)||lmod.after(min))&&(lmod.equals(max)||lmod.before(max)))+"]");
									if (lmod.after(max)) {
                                                                                logger.warn("couldn't rollback update[" + fid + "] [" + dsid+ "] as it has been updated already (asof[" + max + "]<lmod[" + lmod+ "])!");
									} else {
										purgeDatastream(fid, dsid).startDT(min).endDT(max).logMessage("rollback of update").execute();
										logger.debug("update rollback for fid["+ fid +"] dsid["+ dsid+"]");
                                                                        }
								} else {
									logger.warn("couldn't rollback update[" + fid + "] [" + dsid + "] the asof is unknown!");
								}
							} else {
								logger.warn("couldn't rollback update[" + fid + "] [" + dsid+ "] as the resource/collection couldn't be found!");
							}
						} else {
							logger.debug("ignoring the update rollback for fid[" + fid + "] dsid[" + dsid+ "] as no changes happened");
						}
					}
				} catch (Exception ex) {
					logger.error("rollback action[" + this.getName() + "] event[" + event + "] failed!", ex);
				}
			}
		}
	}

        protected Date getNextDatastreamMod(String fid,String dsid,Date last) {
            logger.debug("getNextDatastreamMod(fid["+fid+"],dsid["+dsid+"],last["+last+"])");
            Date nxt=null;
            try {
                GetDatastreamHistoryResponse res = getDatastreamHistory(fid,dsid).execute();
                if (res.getStatus() == 200) {
                    boolean get = false;
                    List profs = res.getDatastreamProfile().getDatastreamProfile();
                    for (ListIterator<DatastreamProfile> iter = profs.listIterator(profs.size()); iter.hasPrevious();) {
                        Date d = iter.previous().getDsCreateDate().toGregorianCalendar().getTime();
                        if (get) {
                            logger.debug("> mod["+d+"]");
                            nxt = d;
                            break;
                        }
                        if (d.equals(last)) {
                            logger.debug("= mod["+d+"]");
                            get = true;
                        } else
                            logger.debug("< mod["+d+"]");
                    }
                } else
                    logger.debug("Unexpected status[" + res.getStatus() + "] while interacting with Fedora Commons!");
            } catch (FedoraClientException e) {
                if (e.getStatus() == 404) {
                    logger.debug("FedoraObject[" + fid + "] and/or datastream[" + dsid + "] doesn't exist!",e);
                } else
                    logger.debug("Unexpected status[" + e.getStatus() + "] while interacting with Fedora Commons!");
            }
            return nxt;
        }
*/
}
