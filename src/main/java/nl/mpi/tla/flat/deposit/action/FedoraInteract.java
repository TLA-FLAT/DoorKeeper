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

import static com.yourmediashelf.fedora.client.FedoraClient.*;
import com.yourmediashelf.fedora.client.FedoraClientException;
import com.yourmediashelf.fedora.client.request.AddDatastream;
import com.yourmediashelf.fedora.client.request.ModifyDatastream;
import com.yourmediashelf.fedora.client.response.AddDatastreamResponse;
import com.yourmediashelf.fedora.client.response.FedoraResponse;
import com.yourmediashelf.fedora.client.response.GetDatastreamHistoryResponse;
import com.yourmediashelf.fedora.client.response.IngestResponse;
import com.yourmediashelf.fedora.client.response.GetDatastreamResponse;
import com.yourmediashelf.fedora.client.response.ModifyDatastreamResponse;
import com.yourmediashelf.fedora.generated.management.DatastreamProfile;
import java.io.File;
import java.io.FilenameFilter;
import java.net.URI;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import javax.xml.transform.stream.StreamSource;
import net.sf.saxon.s9api.XdmItem;
import net.sf.saxon.s9api.XdmNode;
import nl.mpi.tla.flat.deposit.Context;
import nl.mpi.tla.flat.deposit.DepositException;
import nl.mpi.tla.flat.deposit.sip.Collection;
import nl.mpi.tla.flat.deposit.sip.Resource;
import nl.mpi.tla.flat.deposit.sip.SIPInterface;
import static nl.mpi.tla.flat.deposit.util.Global.NAMESPACES;
import nl.mpi.tla.flat.deposit.util.Saxon;
import org.apache.commons.io.filefilter.RegexFileFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import nl.mpi.tla.flat.deposit.util.Global;

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
				String fid = fox.getName().replace(".xml", "").replaceFirst("^([a-z]+)_", "$1:").replace("_CMD","");
				String dsid = (fox.getName().endsWith("_CMD.xml") ? "CMD" : "OBJ");
				logger.debug("FOXML[" + fox + "] -> [" + fid + "]");

				context.registerRollbackEvent(this, "ingest", "fid", fid);

				IngestResponse iResponse = ingest().format("info:fedora/fedora-system:FOXML-1.1").content(fox).logMessage("Initial ingest").ignoreMime(true).execute();
				if (iResponse.getStatus() != 201)
					throw new DepositException("Unexpected status[" + iResponse.getStatus() + "] while interacting with Fedora Commons!");
				Date asof = getObjectProfile(fid).execute().getLastModifiedDate();
				fid = completeFID(sip, new URI(fid), asof).toString();
				logger.info("Created FedoraObject[" + iResponse.getPid() + "][" + iResponse.getLocation() + "][" + dsid+ "][" + asof + "]");
				logger.debug("Should match FID[" + fid + "]");
			}

			// - <fid>.<asof>.props (props -> modify (some) properties)
			foxs = dir.listFiles(((FilenameFilter) new RegexFileFilter("[a-z]+_[A-Za-z0-9_]+\\.[0-9]+\\.props")));
			for (File fox : foxs) {
				String fid = fox.getName().replaceFirst("\\..*$", "").replaceFirst("^([a-z]+)_", "$1:").replace("_CMD", "");
				try {
					String epoch = fox.getName().replaceFirst("^.*\\.([0-9]+)\\.props$", "$1");
					Date asof = new Date(Long.parseLong(epoch));
					logger.debug("Properties[" + fox + "] -> [" + fid + "][" + epoch + "=" + asof + "]");
					XdmNode props = Saxon.buildDocument(new StreamSource(fox));
					for (Iterator<XdmItem> iter = Saxon.xpathIterator(props, "//foxml:property", null, NAMESPACES); iter.hasNext();) {
						XdmItem prop = iter.next();
						String name = Saxon.xpath2string(prop, "@NAME");
						String value = Saxon.xpath2string(prop, "@VALUE");
						if (name.equals("info:fedora/fedora-system:def/model#label")) {

							context.registerRollbackEvent(this, "property", "fid", fid, "prop", "label", "old", getObjectProfile(fid).execute().getLabel(), "new", value, "last", Global.asOfDateTime(getObjectProfile(fid).execute().getLastModifiedDate()));

							FedoraResponse res = modifyObject(fid).lastModifiedDate(asof).label(value).execute();
							if (res.getStatus() != 200)
								throw new DepositException("Unexpected status[" + res.getStatus()+ "] while interacting with Fedora Commons!");
						}
					}
				} catch (FedoraClientException e) {
					if (e.getStatus() == 404) {
						throw new DepositException("FedoraObject[" + fid + "] wasn't created!", e);
					} else
						throw new DepositException("Unexpected status[" + e.getStatus() + "] while querying Fedora Commons!", e);
				}

			}

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

			// - <fid>.<dsid>.<asof>.file ... (DS -> modifyDatastream.dsLocation)
			// - <fid>.<dsid>.<asof>.<ext>... (DS -> modifyDatastream.content)
			foxs = dir.listFiles(((FilenameFilter) new RegexFileFilter("[a-z]+_[A-Za-z0-9_]+\\.[A-Z][A-Z0-9\\-]*\\.[0-9]+\\.[A-Za-z0-9_]+")));
			for (File fox : foxs) {
				String fid = fox.getName().replaceFirst("\\..*$", "").replaceFirst("^([a-z]+)_", "$1:").replace("_CMD", "");
				String dsid = fox.getName().replaceFirst("^.*\\.([A-Z][A-Z0-9\\-]*)\\..*$", "$1");
				String epoch = fox.getName().replaceFirst("^.*\\.([0-9]+)\\..*$", "$1");
				Date asof = new Date(Long.parseLong(epoch));
				String ext = fox.getName().replaceFirst("^.*\\.(.*)$", "$1");
				logger.debug("DSID[" + fox + "] -> [" + fid + "][" + dsid + "][" + epoch + "=" + asof + "][" + ext + "]");
				updateDatastream(context, fox, fid, dsid, asof, ext);
			}
		} catch (Exception e) {
			throw new DepositException("The actual deposit in Fedora failed!", e);
		}
		return true;
	}

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
			context.registerRollbackEvent(this, "update", "fid", fid, "dsid", dsid, "last", Global.asOfDateTime(getDatastream(fid, dsid).execute().getLastModifiedDate()));

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
}
