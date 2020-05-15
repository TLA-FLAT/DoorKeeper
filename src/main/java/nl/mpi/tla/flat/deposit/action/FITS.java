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
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;
import java.util.stream.IntStream;

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
import nl.mpi.tla.flat.deposit.sip.cmdi.CMDResource;
import static nl.mpi.tla.flat.deposit.util.Global.NAMESPACES;
import nl.mpi.tla.flat.deposit.util.Saxon;
import org.apache.commons.io.FileUtils;

/**
 *
 * @author menzowi
 * @author pavi
 */
public class FITS extends AbstractAction {
	private static final Logger logger = LoggerFactory.getLogger(FITS.class);

	private static final String MIMETYPE_XPATH = "distinct-values(/fits:fits/fits:identification/fits:identity/tokenize(@mimetype,'(\\s|,)+'))[.!='TBD']=$mime";
	private Semaphore semaphore;
	XdmNode result;
	int threadCounter = 0;
	ExecutorService executor;

	@Override
	public boolean perform(Context context) throws DepositException {
		int unallowed = 0;
		File dir = null;
		int threadLimit;
		int delayLimit;
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
		if (hasParameter("threadLimit")) {
			threadLimit = Integer.valueOf(getParameter("threadLimit"));
		} else {
			threadLimit = 1; // default number of fits thread
		}
		if (hasParameter("delayLimit")) {
			delayLimit = Integer.valueOf(getParameter("delayLimit"));
		} else {
			delayLimit = 1000; // default delay 1000 milliseconds
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
		threadCounter = 0;
		for (Resource resource : context.getSIP().getResources()) {
			if (resource.hasFile()) {
				File file = resource.getFile();
				logger.debug("resource[" + file + "] mimetype?");
				result = null;
				try {
					// threading of Fits -Start
					URL call = new URL(fitsURL, "examine?file=" + file.getAbsolutePath());
					executor = Executors.newFixedThreadPool(threadLimit);
					TaskLimitSemaphore obj = new TaskLimitSemaphore(executor, threadLimit);
					int loopCounter = threadCounter;
					while (loopCounter < threadLimit) {
						loopCounter++;
						if (threadCounter<threadLimit) {
						    obj.submit(() -> {
								threadCounter++;
								logger.info("Thread " + threadCounter + " running. . . .");
								result = Saxon.buildDocument(new StreamSource(call.toString()));
								Thread.sleep(delayLimit); 
								logger.info("Thread " + threadCounter + " Done. . . .");
								return threadCounter;
							});
							threadCounter--;
						}
					}
					executor.shutdown();
					// threading of Fits -End

					// URL call = new URL(fitsURL, "examine?file=" + file.getAbsolutePath());
					// result = Saxon.buildDocument(new StreamSource(call.toString()));
				} catch (Exception ex) {
					throw new DepositException(ex);
				}
				//Check if all the threads are completed - Start
				while(result==null) {
				try { 
					  executor.awaitTermination(15, TimeUnit.SECONDS);
					} catch (InterruptedException e) {
						throw new DepositException(e);
					}
				}
				//Check if all the threads are completed - End
				if (dir != null && result!=null) {
					// save the FITS report for this resource
					String name = file.getPath().replaceAll("[^a-zA-Z0-9\\-]", "_");
					if (resource instanceof CMDResource)
						name = ((CMDResource) resource).getID();
					File out = new File(dir + "/" + name + ".FITS.xml");
					try {
						Saxon.save(result.asSource(), out);
					} catch (SaxonApiException ex) {
						throw new DepositException(ex);
					}
					logger.debug(". FITS[" + out + "]");
				}
				try {
					// loop over /mimetypes/mimetype
					boolean bCheck1 = false; // tells if a mimetype was found for the resource
					for (Iterator<XdmItem> iter = Saxon.xpathIterator(mimetypes, "/mimetypes/mimetype", null,
							NAMESPACES); iter.hasNext();) {
						XdmItem mt = iter.next();
						String mime = Saxon.xpath2string(mt, "normalize-space(@value)");
						logger.debug(". . mimetype[" + mime + "] check");
						Boolean bCheck2 = new Boolean(true); // tells if all assertion groups succeeded
						if (Saxon.xpath2boolean(mt, "exists(assertions)")) {
							for (Iterator<XdmItem> iter2 = Saxon.xpathIterator(mt, "assertions", null,
									NAMESPACES); iter2.hasNext();) {
								XdmItem assertions = iter2.next();
								// get the xpath
								String xp = Saxon.xpath2string(assertions, "normalize-space(@xpath)");
								if (xp.equals("")) {
									// no xpath, so fall back to the default xpath
									xp = MIMETYPE_XPATH;
								}
								logger.debug(". . . assertions[" + xp + "] check");
								// evaluate xpath
								Map vars = new HashMap();
								vars.put("mime", new XdmAtomicValue(mime));
								if (!Saxon.xpath2boolean(result, xp, vars, NAMESPACES)) {
									// the assertions XPath failed, continue to the next /mimetypes/mimetype
									logger.debug(". . . assertions[" + xp + "] check failed");
									bCheck2 = null;
									break;
								}
								logger.debug(". . . assertions[" + xp + "] check succeeded");
								// the assertions XPath succeeded, check the assertions for this mimetype
								Boolean bCheck3 = true; // tells if all mimetype assertions succeeded for the resource
								for (Iterator<XdmItem> iter3 = Saxon.xpathIterator(assertions, "assert", null,
										NAMESPACES); iter3.hasNext();) {
									XdmItem a = iter3.next();
									String axp = Saxon.xpath2string(a, "normalize-space(@xpath)");
									if (!axp.isEmpty()) {
										// evaluate the assertion xpath
										bCheck3 = Saxon.xpath2boolean(result, axp, null, NAMESPACES);
										if (!bCheck3) {
											// assertion fails, print the AVT log message
											logger.debug(". . . . assert[" + axp + "] check failed");
											logger.error(
													"File '{}' has a mimetype '{}' which is ALLOWED in this repository, but fails an assertion!",
													file, mime);
											logger.error("Message from FITS file: "
													+ Saxon.avt(Saxon.xpath2string(a, "@message"), result,
															context.getProperties(), NAMESPACES));
											// break out of the assertion loop
											break;
										}
										// assertion is positive, go to next
										logger.debug(". . . . assert[" + axp + "] check succeeded");
									} else {
										// the assertion xpath does not exist
										throw new DepositException("The verification of the FITS report for resource["
												+ file + "] failed due to a configuration mismatch!");
									}
								}
								if (!bCheck3) {
									// some assertion of this assertions failed
									logger.debug(". . . assertions[" + xp + "] failed");
									bCheck2 = new Boolean(false);
									break;
								} else
									logger.debug(". . . assertions[" + xp + "] succeeded");
							}
						} else {
							// no assertions, use just the path
							String xp = Saxon.xpath2string(mt, "normalize-space(@xpath)");
							if (xp.equals("")) {
								// no xpath, so fall back to the default xpath
								xp = MIMETYPE_XPATH;
							}
							// evaluate xpath
							Map vars = new HashMap();
							vars.put("mime", new XdmAtomicValue(mime));
							if (!Saxon.xpath2boolean(result, xp, vars, NAMESPACES)) {
								// the assertions XPath failed, continue to the next /mimetypes/mimetype
								logger.debug(". . . assertions[" + xp + "] check failed");
								bCheck2 = null;
								continue;
							}
							logger.debug(". . . assertions[" + xp + "] check succeeded");
						}
						if (bCheck2 == null) {
							logger.debug(". . continue to next mimetype");
							continue;
						}
						if (bCheck2.booleanValue()) {
							// all assertions succeeded
							logger.debug(". . mimetype[" + mime + "] succeeded");
							bCheck1 = true;
							logger.info(
									"Resource[{}] has a mimetype which is ALLOWED in this repository and satisfies all assertions: '{}'",
									file, mime);
							if (resource.hasMime() && !resource.getMime().equals(mime)) {
								logger.warn("Resource mimetype changed from '{}' to '{}'", resource.getMime(), mime);
							}
							logger.debug("Setting resource mimetype to '{}'", mime);
							resource.setMime(mime);
						} else
							logger.debug(". . mimetype[" + mime + "] failed");
						break;
					}

					if (!bCheck1) {
						// no mimetype was found, look for the otherwise
						logger.debug(". mimetypes failed, checking otherwise");
						XdmItem o = Saxon.xpathSingle(mimetypes, "/mimetypes/otherwise");
						if (o != null) {
							// check for an xpath
							String oxp = Saxon.xpath2string(o, "normalize-space(@xpath)");
							if (oxp.equals("") || Saxon.xpath2boolean(result, oxp, null, NAMESPACES)) {
								// no xpath or succesfull xpath, use fallback value as mimetype
								String fallback = Saxon.xpath2string(o, "normalize-space(@value)");
								if (!fallback.equals("")) {
									// use the non-empty fallback value as mimetype for the resource
									bCheck1 = true;
									logger.error("Use fallback mimetype[{}] for resource[{}]", fallback, file);
									if (resource.hasMime() && !resource.getMime().equals(fallback)) {
										logger.warn("Resource mimetype changed from '{}' to '{}'", resource.getMime(),
												fallback);
									}
									logger.debug("Setting resource mimetype to '{}'", fallback);
									resource.setMime(fallback);
								}
							}
						} else
							logger.debug(". mimetypes failed, no otherwise");

						if (!bCheck1) {
							// no allowed or fallback mimetype was found for this resource
							logger.debug(". mimetypes failed");
							logger.error("No mimetype found for resource[{}]", file);
							unallowed++;
						} else
							logger.debug(". mimetypes succeeded");
					}
				} catch (Exception ex) {
					throw new DepositException(ex);
				}
			}
		}
		if (unallowed > 0)
			logger.error("{} resources were not allowed!", unallowed);
		return (unallowed == 0);
	}

	public class TaskLimitSemaphore {

		private final ExecutorService executor;
		private final Semaphore semaphore;

		public TaskLimitSemaphore(ExecutorService executor, int limit) {
			this.executor = executor;
			this.semaphore = new Semaphore(limit);
		}

		public <T> Future<T> submit(final Callable<T> task) throws InterruptedException {

			semaphore.acquire();
			logger.info("semaphore.acquire()...");

			return executor.submit(() -> {
				try {
					return task.call();
				} finally {
					semaphore.release();
					logger.info("semaphore.release()...");
				}
			});
		}
	}
}