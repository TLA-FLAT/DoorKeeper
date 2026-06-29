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
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import javax.xml.transform.stream.StreamSource;
import net.sf.saxon.s9api.XdmAtomicValue;
import net.sf.saxon.s9api.XdmItem;
import net.sf.saxon.s9api.XdmNode;
import nl.mpi.tla.flat.deposit.Context;
import nl.mpi.tla.flat.deposit.DepositException;
import nl.mpi.tla.flat.deposit.sip.SIPInterface;
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

			SIPInterface sip = context.getSIP();

			File dir = new File(this.getParameter("dir", "./fox"));

			// <fid>.xml (FOXML -> ingest)
			File[] foxs = dir.listFiles(((FilenameFilter) new RegexFileFilter("[a-z]+_[A-Za-z0-9_]+\\.xml")));
			for (File fox : foxs) {
                            
				String fid = fox.getName().replace(".xml", "").replaceFirst("^([a-z]+)_", "$1_").replace("_CMD","");
				String dsid = (fox.getName().endsWith("_CMD.xml") ? "CMD" : "OBJ");
				logger.debug("FOXML[" + fox + "] -> [" + fid + "]");
                                logger.debug("TODO: ingest not yet implemented!");

				/*
                                context.registerRollbackEvent(this, "ingest", "fid", fid);

				IngestResponse iResponse = ingest().format("info:fedora/fedora-system:FOXML-1.1").content(fox).logMessage("Initial ingest").ignoreMime(true).execute();
				if (iResponse.getStatus() != 201)
					throw new DepositException("Unexpected status[" + iResponse.getStatus() + "] while interacting with Fedora Commons!");
				Date asof = getObjectProfile(fid).execute().getLastModifiedDate();
				fid = completeFID(sip, new URI(fid), asof).toString();
				logger.info("Created FedoraObject[" + iResponse.getPid() + "][" + iResponse.getLocation() + "][" + dsid+ "][" + asof + "]");
				logger.debug("Should match FID[" + fid + "]");
                                */
			}

			// - <fid>.<asof>.props (props -> modify (some) properties)
                        File[] propfiles = dir.listFiles(((FilenameFilter) new RegexFileFilter("[a-z]+_[A-Za-z0-9_]+\\.[0-9]+\\.props")));
			for (File propfile : propfiles) {
				String fid = propfile.getName().replaceFirst("\\..*$", "").replaceFirst("^([a-z]+)_", "$1_").replace("_CMD", "");
                                XdmNode ds = fcrepo(new URI(fid));
				try {
					String epoch = propfile.getName().replaceFirst("^.*\\.([0-9]+)\\.props$", "$1");
					Date asof = new Date(Long.parseLong(epoch));
					logger.debug("Properties[" +  propfile + "] -> [" + fid + "][" + epoch + "=" + asof + "]");
					XdmNode props = Saxon.buildDocument(new StreamSource(propfile));
					for (Iterator<XdmItem> iter = Saxon.xpathIterator(props, "distinct-values(//foxml:property/@NAME)", null, NAMESPACES); iter.hasNext();) {
						XdmItem prop = iter.next();
						String name = prop.getStringValue();
                                                logger.debug("Property[" + name + "]");
						List<XdmItem> vals = Saxon.xpathList(props, "//foxml:property[@NAME='"+name+"']/@VALUE", null, NAMESPACES);
                                                if (name.equals("info:fedora/fedora-system:def/model#state")) {
                                                    var vs = new ArrayList<XdmItem>();
                                                    for (XdmItem val:vals) {
                                                        String v = switch(val.getStringValue()) {
                                                            case "A" -> "Active";
                                                            case "I" -> "Inactive";
                                                            case "D" -> "Deleted";
                                                            default -> val.getStringValue();
                                                        };
                                                        vs.add(new XdmAtomicValue(v));
                                                    }
                                                }
                                                upsertProperty(context,ds,fid,name,vals);
					}
				} catch (Exception e) {
                                        throw new DepositException("Unexpected response[" + e + "] while querying Fedora Commons!", e);
				}

			}
                        /*

			// - <fid>.<dsid>.file ... create/modify DS
			// - <fid>.<dsid>.<ext>... create/modify DS
			foxs = dir.listFiles(
					((FilenameFilter) new RegexFileFilter("[a-z]+_[A-Za-z0-9_]+\\.[A-Z][A-Z0-9\\-]*\\.[A-Za-z0-9_]+")));
			for (File fox : foxs) {
				String fid = fox.getName().replaceFirst("\\..*$", "").replaceFirst("^([a-z]+)_", "$1:").replace("_CMD", "");
				String dsid = fox.getName().replaceFirst("^.*\\.([A-Z][A-Z0-9\\-]*)\\..*$", "$1");
				String ext = fox.getName().replaceFirst("^.*\\.(.*)$", "$1");
				logger.debug("DSID[" + fox + "] -> [" + fid + "][" + dsid + "][" + ext + "]");
				upsertDatastream(context, fox, fid, dsid, ext);
			}
                        */

			// - <fid>.<dsid>.<asof>.file ... (DS -> modifyDatastream.dsLocation)
			// - <fid>.<dsid>.<asof>.<ext>... (DS -> modifyDatastream.content)
			foxs = dir.listFiles(((FilenameFilter) new RegexFileFilter("[a-z]+_[A-Za-z0-9_]+\\.[A-Z][A-Z0-9\\-]*\\.[0-9]+\\.[A-Za-z0-9_]+")));
			for (File fox : foxs) {
				String fid = fox.getName().replaceFirst("\\..*$", "").replaceFirst("^([a-z]+)_", "$1_").replace("_CMD", "");
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
        
        protected String toSPARQL_URI(String val) {
            if (val.startsWith("http:") || val.startsWith("https:")) {
                val = "<"+val+">";
            } else { 
                val = "'"+val.replace("'", "\\'")+"'";
            }
            return val;
        }
        
        protected void upsertProperty(Context context, String fid, String prop, List<XdmItem> vals)  throws DepositException {
            try {
                upsertProperty(context,fcrepo(new URI(fid)),fid,prop,vals);
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
                String NL = System.getProperty("line.separator");
                String sparql = "PREFIX dc: <http://purl.org/dc/elements/1.1/>";
                sparql += NL;
                String sparql_template = "DELETE { ?ds <%s> %s }";
                for (XdmItem oldval:oldvals) {
                    String old = oldval.getStringValue();
                    old = toSPARQL_URI(old);
                    context.registerRollbackEvent(this, "property", "fid", fid, "prop", prop, "old", old);
                    sparql += sparql_template.formatted(prop,old);
                    sparql += NL; 
                }
                sparql_template = "INSERT { ?ds <%s> %s }";
                for (XdmItem newval:vals) {
                    String val = newval.getStringValue();
                    val = toSPARQL_URI(val);
                    context.registerRollbackEvent(this, "property", "fid", fid, "prop", prop, "new", val);
                    sparql += sparql_template.formatted(prop,val);
                    sparql += NL; 
                }
                sparql_template = "WHERE  { ?ds dc:identifier '%s' }";
                sparql += sparql_template.formatted(rfid);
                sparql += NL; 
                logger.debug("rfid[" +rfid + "] prop["+prop+"] sparql["+sparql+"]");                                                        
                FcrepoResponse response = (new PatchBuilder(new URI(rfid),fedoraClient)).body(IOUtils.toInputStream(sparql)).perform();
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
                             PREFIX dc: <http://purl.org/dc/elements/1.1/>
                             INSERT { ?ds <http://purl.org/dc/elements/1.1/identifier> '%s' }
                             WHERE  { ?ds dc:identifier '%s' }""".indent(2);
                           context.registerRollbackEvent(this, "property", "fid", fid, "prop", "http://purl.org/dc/elements/1.1/identifier", "val", val.getStringValue());
                            String sparql=sparql_template.formatted(val,fid);
                            logger.debug("rfid[" +rfid + "] prop[http://purl.org/dc/elements/1.1/identifier] val["+val+"] sparql["+sparql+"]");                                                        
                            FcrepoResponse response = (new PatchBuilder(new URI(rfid),fedoraClient)).addTransaction(new URI(context.getFromMemory("transLocation").toString())).body(IOUtils.toInputStream(sparql)).perform();
                        } else if (!oldval.strip().equals(val.getStringValue().strip())) {
                            // update
                           String sparql_template= """
                             PREFIX dc: <http://purl.org/dc/elements/1.1/>
                             DELETE { ?ds <http://purl.org/dc/elements/1.1/identifier> '%s' }
                             INSERT { ?ds <http://purl.org/dc/elements/1.1/identifier> '%s' }
                             WHERE  { ?ds dc:identifier '%s' }""".indent(2);
                           context.registerRollbackEvent(this, "property", "fid", fid, "prop", "http://purl.org/dc/elements/1.1/identifier", "old", oldval, "new", val.getStringValue());
                            String sparql=sparql_template.formatted(oldval,val,fid);
                            logger.debug("rfid[" +rfid + "] prop[http://purl.org/dc/elements/1.1/identifier] old["+oldval+"] new["+val+"] sparql["+sparql+"]");                                                        
                            FcrepoResponse response = (new PatchBuilder(new URI(rfid),fedoraClient)).body(IOUtils.toInputStream(sparql)).perform();
                        } else
                            logger.debug("SKIP: rfid[" +rfid + "] prop[http://purl.org/dc/elements/1.1/identifier] old["+oldval+"] new["+val+"] noop");
                    } else
                        logger.debug("SKIP: rfid[" +rfid + "] prop[http://purl.org/dc/elements/1.1/identifier] new["+val+"] not md5");
                }
            } catch (Exception ex) {
                throw new DepositException(ex);
            }
        }
        protected void upsertDatastream(Context context, File fox, String fid, String ds, String ext) throws DepositException {
            try {
                Boolean exists = fcrepo_exists(new URI(fid),ds);
                if (exists!=null && exists.booleanValue())
                    updateDatastream(context, fox, fid, ds, ext);
                else
                    insertDatastream(context, fox, fid, ds, ext);
            } catch (Exception e) {
                throw new DepositException(e);   
            }
        }
        
        protected void insertDatastream(Context context, File fox, String fid, String ds, String ext) throws DepositException {
            logger.debug("TODO: insertDatastream["+ds+"] not yet implemented!");
            // what to do with the various dsid's?
            // DC -> verwerken in de RDF voor de FID, e.g. dc:identifier naar de md5:...
            // CMD -> nieuwe binary, zie https://wiki.lyrasis.org/display/FEDORA6x/External+Content met proxy
            // OBJ -> nieuwe binary, zie https://wiki.lyrasis.org/display/FEDORA6x/External+Content met proxy
            // RELS-EXT -> verwerken in de RDF voor de FID, e.g. isConstituentOf
            // TN -> skip
        }
        
        void updateDatastream(Context context, File fox, String fid, String ds, String ext) throws DepositException {
            logger.debug("CHECK: updateDatastream["+fid+"]["+ds+"]");
            if (ds.equals("DC")) {
                logger.debug("DO: updateDatastream["+fid+"]["+ds+"]");
                try {
                    XdmNode dc = Saxon.buildDocument(new StreamSource(fox));
                    for (Iterator<XdmItem> iter = Saxon.xpathIterator(dc, "//dc:*", null, NAMESPACES); iter.hasNext();) {
                        XdmItem prop = iter.next();
                        String name = Saxon.xpath2string(prop, "concat(namespace-uri(),local-name())",null,NAMESPACES);
                        var vals = Saxon.xpathList(prop, ".",null,NAMESPACES);
                        upsertProperty(context,fid,name,vals);
                    }
                } catch (Exception ex) {
                    throw new DepositException(ex);
                }
            } else if (ds.equals("OBJ")) {
                logger.debug("DO: updateDatastream["+fid+"]["+ds+"]");
                try {
                    String rest = fedoraConfig.getString("localServer");
                    String rfid = rest+"/"+fid+"/"+ds;
                    XdmNode loc = Saxon.buildDocument(new StreamSource(fox));
                    String content = Saxon.xpath2string(loc,"/foxml:datastreamVersion/foxml:contentLocation[1]/@REF",null,NAMESPACES);
                    String mime = Saxon.xpath2string(loc,"/foxml:datastreamVersion/@MIMETYPE",null,NAMESPACES);
                    logger.debug("UPDATE external content["+rfid+"] set to ["+content+"]["+mime+"]");
                    FcrepoResponse response = (new PutBuilder(new URI(rfid),fedoraClient)).externalContent(new URI(content), mime, "proxy").perform();
                    logger.debug("FCREPO code["+response.getStatusCode()+"]");
                    if (response.getStatusCode() > 300)
                         throw new DepositException("can't update the external content of ["+rfid+"]");   
                } catch (Exception ex) {
                    throw new DepositException(ex);
                }
            } else if (ds.equals("CMD")) {
                logger.debug("DO: updateDatastream["+fid+"]["+ds+"]["+fox.getAbsolutePath()+"]");
                try {
                    String rest = fedoraConfig.getString("localServer");
                    String rfid = rest+"/"+fid+"/"+ds;
                    FcrepoResponse response = (new PutBuilder(new URI(rfid),fedoraClient)).body(new FileInputStream(fox),"application/xml").perform();
                    logger.debug("FCREPO code["+response.getStatusCode()+"]");
                } catch (Exception ex) {
                    throw new DepositException(ex);
                }
            } else if (ds.equals("RELS-EXT")) {
                logger.debug("DO: updateDatastream["+fid+"]["+ds+"]");
                try {
                    XdmNode rels = Saxon.buildDocument(new StreamSource(fox));
                    String[] prefixes = {"relsext","model","onto-relsext","oai"};
                    for (String prefix:prefixes) {
                        logger.debug("DO: updateDatastream["+fid+"]["+ds+"]["+prefix+"]["+Saxon.xpath(rels, "(//"+prefix+":*)[exists((.,@rdf:resource)[normalize-space(.)!=''])]", null, NAMESPACES).size()+"]");
                        for (Iterator<XdmItem> iter = Saxon.xpathIterator(rels, "(//"+prefix+":*)[exists((.,@rdf:resource)[normalize-space(.)!=''])]", null, NAMESPACES); iter.hasNext();) {
                            XdmItem prop = iter.next();
                            String name = Saxon.xpath2string(prop, "concat(namespace-uri(),local-name())", null, NAMESPACES);
                        logger.debug("DO: updateDatastream["+fid+"]["+ds+"]["+prefix+"]["+name+"]");
                            var vals = Saxon.xpathList(prop, "(.,@rdf:resource)[normalize-space(.)!=''][1]", null, NAMESPACES);
                        logger.debug("DO: updateDatastream["+fid+"]["+ds+"]["+prefix+"]["+name+"]["+prop.getStringValue()+"]["+vals+"]");
                            upsertProperty(context,fid,name,vals);
                        }
                    }
                } catch (Exception ex) {
                    throw new DepositException(ex);
                }
            } else
                logger.debug("TODO: updateDatastream["+fid+"]["+ds+"] not yet implemented!");
            // what to do with the various dsid's?
            // DC  -> DONE
            // CMD -> DONE
            // OBJ -> DONE
            // RELS-EXT ->  a la other props?
            // TN -> external location update
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
