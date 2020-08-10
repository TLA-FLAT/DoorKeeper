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

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.IntConsumer;
import java.util.stream.IntStream;

import javax.swing.text.Document;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Source;
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
	XdmNode result;
	int threadCounter = 0;
	ExecutorService executor;
	File dir;
	int unallowed;
	XdmNode mimetypes;
	private static volatile boolean isAnyError;

	@Override
	public boolean perform(Context context) throws DepositException {
		isAnyError = false;
		int threadLimit;
		int waitLimit;
		dir = null;
		unallowed = 0;
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
		if (hasParameter("waitLimit")) {
			waitLimit = Integer.valueOf(getParameter("threadLimit"));
		} else {
			waitLimit = 15; // default waiting of fits thread
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
		mimetypes = null;
		try {
			mimetypes = Saxon.buildDocument(new StreamSource(mimetypesFileLocation));
		} catch (SaxonApiException ex) {
			throw new DepositException(ex);
		}
		threadCounter = 0;
		executor = Executors.newFixedThreadPool(threadLimit);
		TaskLimitSemaphore obj = new TaskLimitSemaphore(executor, threadLimit);
		List<Future<Integer>> list = new ArrayList<Future<Integer>>();

		for (Resource resource : context.getSIP().getResources()) {
			if (!isAnyError) {
				Future<Integer> future;
				if (resource.hasFile()) {
					File file = resource.getFile();
					logger.debug("resource[" + file + "] mimetype?");
					result = null;
					try {
						URL call = new URL(fitsURL, "examine?file=" + file.getAbsolutePath());
						if (threadCounter <= threadLimit) {
							future = obj.submit(() -> {
								threadCounter = threadCounter + 1;
								logger.debug("Running: Thread name = " + Thread.currentThread().getName());
								logger.debug("Running: Thread counter = " + threadCounter);
								try {
									logger.debug("URL call = " + call.toString());
									HttpURLConnection con = (HttpURLConnection) call.openConnection();
									con.setRequestMethod("GET");
									con.connect();
									int status = -1;
									try {
										status = con.getResponseCode();
									} catch (IOException e) {
										isAnyError = true;
										logger.debug("set isAnyError TRUE");
										con.disconnect();
										logger.debug("Exception occurred while getting response code: " + e.getMessage());
										throw new DepositException("Status from getResponseCode() incorrect. Abort!");
									}
									logger.debug("status from the http call: " + status);
									Reader streamReader = null;
									StringBuffer content;
									if (status > 299 || status == -1) {
										isAnyError = true;
										logger.debug("set isAnyError TRUE");
										streamReader = new InputStreamReader(con.getErrorStream());
										con.disconnect();
										logger.debug("Status from the Http call is >299. Hence abort! Error: "+ streamReader);
										throw new DepositException("Status from Http call returning error value. Abort!");
									} else if (status < 299) {
										streamReader = new InputStreamReader(con.getInputStream());
										logger.debug("StreamReader : " + streamReader);
										content = new StringBuffer();
										BufferedReader in = new BufferedReader(streamReader);
										String inputLine;
										while ((inputLine = in.readLine()) != null) {
											content.append(inputLine);
										}
										in.close();
										con.disconnect();
										logger.debug("Content created!");
									} else {
										isAnyError = true;
										logger.debug("set isAnyError TRUE");
										con.disconnect();
										logger.debug("Status from the Http call does not satisfy the condition to proceed further!");
										throw new DepositException("Status from Http call returning unknown value. Abort!");
									}

									if (String.valueOf(content) == null) {
										isAnyError = true;
										logger.debug("set isAnyError TRUE");
										logger.debug("Content from Http Call is NULL!!");
										throw new DepositException("Content from the Http call is null. Abort the execution!!");
									}

									Source ss = new StreamSource(new StringReader(content.toString().trim()));
									logger.debug("Source: " + ss);

									if (ss.toString() != null) {
										result = Saxon.buildDocument(ss);
										if (dir != null && result != null) {
											logger.debug("Result = " + result.getStringValue());
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
										} else {
											isAnyError = true;
											logger.debug("set isAnyError TRUE");
											logger.debug("Result from Fits: " + result + " does not satisfy the condition.");
											logger.debug("Hence the Fits value is not saved and further execution is aborted!");
											throw new DepositException("Result from Fits is incorrect. Abort!");
										}
									} else {
										isAnyError = true;
										logger.debug("set isAnyError TRUE");
										logger.debug("XML Document is equal to null i.e Fits returned null");
										throw new DepositException("Mimetype will not be checked due to Fits returning null value!");
									}
								} catch (IOException io) {
									throw new IOException("IOException occured during the http call: " + io + io.getMessage());
								}
								try {
									// loop over /mimetypes/mimetype
									boolean bCheck1 = false; // tells if a mimetype was found for the resource
									for (Iterator<XdmItem> iter = Saxon.xpathIterator(mimetypes, "/mimetypes/mimetype",
											null, NAMESPACES); iter.hasNext();) {
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
													// the assertions XPath failed, continue to the next
													// /mimetypes/mimetype
													logger.debug(". . . assertions[" + xp + "] check failed");
													bCheck2 = null;
													break;
												}
												logger.debug(". . . assertions[" + xp + "] check succeeded");
												// the assertions XPath succeeded, check the assertions for this
												// mimetype
												Boolean bCheck3 = true; // tells if all mimetype assertions succeeded
																		// for
																		// the resource
												for (Iterator<XdmItem> iter3 = Saxon.xpathIterator(assertions, "assert",
														null, NAMESPACES); iter3.hasNext();) {
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
															logger.error("Message from FITS file: " + Saxon.avt(
																	Saxon.xpath2string(a, "@message"), result,
																	context.getProperties(), NAMESPACES));
															// break out of the assertion loop
															break;
														}
														// assertion is positive, go to next
														logger.debug(". . . . assert[" + axp + "] check succeeded");
													} else {
														// the assertion xpath does not exist
														throw new DepositException(
																"The verification of the FITS report for resource["
																		+ file
																		+ "] failed due to a configuration mismatch!");
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
												logger.warn("Resource mimetype changed from '{}' to '{}'",
														resource.getMime(), mime);
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
													logger.error("Use fallback mimetype[{}] for resource[{}]", fallback,
															file);
													if (resource.hasMime() && !resource.getMime().equals(fallback)) {
														logger.warn("Resource mimetype changed from '{}' to '{}'",
																resource.getMime(), fallback);
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
								logger.debug("Closing: Thread name = " + Thread.currentThread().getName());
								logger.debug("Closing: Thread counter = " + threadCounter + " Done. . . .");
								return threadCounter;
							});
							list.add((Future<Integer>) future);
						}
					} catch (Exception ex) {
						throw new DepositException(ex);
					}
				}
			} else {
				logger.debug("ERROR in one of the threads! Abort execution! (isAnyError) --> " + isAnyError);
			}
		}
		// Check if all the threads are completed - Start
		while (threadCounter > 0) {
			try {
				logger.debug("Threadcounter > 0 ---> " + threadCounter + " > 0");
				logger.debug("Wait for all threads to finish (" + waitLimit + " seconds). . . . . . . . . . . . . . . .");
				executor.awaitTermination(waitLimit, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				throw new DepositException(e);
			}
		}

		for (Future<Integer> fut : list) {
			try {
				// because Future.get() waits for task to get completed
				logger.info(new Date() + "::" + fut.get());
			} catch (InterruptedException | ExecutionException e) {
				throw new DepositException("Exception occurred from thread with msg: " + e);
			}
		}

		logger.info("Execution of all threads successfully completed! We can now safely close the executor.");
		// Check if all the threads are completed - End
		executor.shutdown();
		logger.info("Executor shutdown done!");

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

		public <T> Future<T> submit(final Callable<T> task) throws Exception {
			Future<T> future = null;
			logger.debug("isAnyError Boolean value inside thread: "+isAnyError);
			if (isAnyError) {
				throw new Exception("ERROR! So don't acquire anymore threads!");
			} else {
				semaphore.acquire();
				logger.debug("semaphore.acquire()...");
			}
			try {
				future = executor.submit(() -> {
					try {
						return task.call();
					} finally {
						semaphore.release();
						logger.debug("semaphore.release()...");
						threadCounter--;
						logger.debug("Thread counter decreased to --> " + threadCounter + " . . . . . . .");

					}
				});
			} catch (Exception e) {
				isAnyError = true;
				semaphore.release();
				logger.debug("ERROR: semaphore.release()...");
				threadCounter--;
				logger.debug("ERROR: Thread counter decreased to --> " + threadCounter + " . . . . . . .");
				throw new Exception("ERROR occurred in thread! -> " + e);
			}
			return future;
		}
	}
}