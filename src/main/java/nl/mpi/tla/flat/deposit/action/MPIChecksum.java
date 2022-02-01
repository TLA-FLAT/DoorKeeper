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
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.xml.transform.stream.StreamSource;

import net.sf.saxon.s9api.XdmNode;
import nl.mpi.tla.flat.deposit.Context;
import nl.mpi.tla.flat.deposit.DepositException;
import nl.mpi.tla.flat.deposit.sip.Resource;
import nl.mpi.tla.flat.deposit.sip.SIPInterface;
import nl.mpi.tla.flat.deposit.sip.cmdi.CMDResource;
import nl.mpi.tla.flat.deposit.util.Saxon;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author pavi
 */
public class MPIChecksum extends AbstractAction {

	private static final Logger logger = LoggerFactory.getLogger(MPIChecksum.class.getName());

	public boolean perform(Context context) throws DepositException {
		boolean res = true;
		try {
			// connect(context);
			String checksumConfigParam = this.getParameter("checksum-config");
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

			// Check the checksum-config file
			File checksumConfig = new File(checksumConfigParam);
			if (!checksumConfig.exists()) {
				logger.error("The Checksum configuration[" + checksumConfigParam + "] doesn't exist!");
				return false;
			} else if (!checksumConfig.isFile()) {
				logger.error("The Checksum configuration[" + checksumConfigParam + "] isn't a file!");
				return false;
			} else if (!checksumConfig.canRead()) {
				logger.error("The Checksum configuration[" + checksumConfigParam + "] can't be read!");
				return false;
			}
			logger.debug("Fedora configuration[" + checksumConfig.getAbsolutePath() + "]");

			// retrieve login credentials and command to execute from checksum-config file
			XdmNode nChecksum = Saxon.buildDocument(new StreamSource(checksumConfig));
			XdmNode fileSystem = (XdmNode) Saxon.xpathSingle(nChecksum, "/checksum/file-system");
			String login;
			String sysCommand;
			if (fileSystem != null) {
				login = Saxon.xpath2string(fileSystem, "./user");
				sysCommand = Saxon.xpath2string(fileSystem, "./sys-cmd");
				logger.debug("Credentials User(checksum-config.xml): " + login);
			} else {
				throw new DepositException("FAILED! No credentials found in file checksum-config.xml");
			}

			SIPInterface sip = context.getSIP();
			Set<Resource> resources = sip.getResources();
			for (Resource currentResource : resources) {
				if (currentResource.hasFile()) {
					File currentFile = currentResource.getFile();
					String absPath = currentFile.getAbsolutePath();
					absPath = absPath.replace(" ", "\\ ");
					// sysCommand workaround uses intermediate shell script to avoid quotation issues with processbuilder and ssh
					String command = sysCommand + " " + login + " " + absPath;
					logger.debug("Command: " + command.toString());

					ProcessBuilder pb = new ProcessBuilder("/bin/sh","-c",command);
					pb.directory(new File(dir.getAbsolutePath()));
					pb.redirectErrorStream(true);

					Process process = pb.start();
					InputStream is = process.getInputStream();
					InputStreamReader isr = new InputStreamReader(is);
					BufferedReader br = new BufferedReader(isr);

					String line;
					line = br.readLine();

					// Wait to get exit value
					int exitValue = process.waitFor();
					logger.debug("Exit Value is " + exitValue);
					logger.debug("Line Value is " + line);
					if (exitValue != 0) {
						logger.error("Command to fetch md5Checksum exited with non-zero value. Failed!");
						br.close();
						isr.close();
						res = false;
					} else if (StringUtils.isEmpty(line) || line.equals("00000000000000000000000000000000")) {
						br.close();
						isr.close();
						// md5checksum is not present yet. hence calculate

						XdmNode fallback = (XdmNode) Saxon.xpathSingle(nChecksum, "/checksum/fallback");
						String fallbackLogin;
						String fallbackCommand;
						if (fallback != null) {
							fallbackLogin = Saxon.xpath2string(fallback, "./user");
							fallbackCommand = Saxon.xpath2string(fallback, "./sys-cmd");
							logger.debug("Fallback Credentials User(checksum-config.xml): " + fallbackLogin);
						} else {
							throw new DepositException(
									"FAILED! No credentials found in file checksum-config.xml for Fallback situation");
						}

						String calcCommand;
						if (StringUtils.isEmpty(fallbackLogin)) {
							calcCommand = fallbackCommand + " '" + absPath + "'";
							logger.debug("Local Calculate md5Checksum Command: " + calcCommand.toString());
						} else {
							// fallbackCommand workaround uses intermediate shell script to avoid quotation issues with processbuilder and ssh
							calcCommand = fallbackCommand + " " + login + " " + absPath;
							logger.debug("Remote Calculate md5Checksum Command: " + calcCommand.toString());
						}

						ProcessBuilder calcPb = new ProcessBuilder("/bin/sh","-c",calcCommand);
						calcPb.directory(new File(dir.getAbsolutePath()));
						calcPb.redirectErrorStream(true);

						Process calcProcess = calcPb.start();
						InputStream calcIs = calcProcess.getInputStream();
						InputStreamReader calcIsr = new InputStreamReader(calcIs);
						BufferedReader calcBr = new BufferedReader(calcIsr);

						String calcLine;
						calcLine = calcBr.readLine();

						// Wait to get exit value
						int calcExitValue = calcProcess.waitFor();
						logger.debug("Exit Value is " + calcExitValue);
						logger.debug("Line Value is " + calcLine);
						if (calcExitValue != 0) {
							logger.error("Failed to calculate MD5 checksum value in system!");
							res = false;
						}
						calcBr.close();
						calcIsr.close();
						line = calcLine;
					}
					if (res) {
						logger.debug("MD5 checksum successfully retrieved: " + line);
						br.close();
						isr.close();
						if (!StringUtils.isEmpty(line)) {
							// check for strings other than md5(of 32 char) in line and strip them off
							if (line.length() > 32) {
								line = line.substring(0, 32);
								logger.debug("Stripped Line Value is " + line);
							}
							if (dir != null) {
								String name = ((CMDResource) currentResource).getID();
								File out = new File(dir + "/" + name + ".xml");
								try {
									PrintWriter w = new PrintWriter(out);
									w.print("<md5>" + line + "</md5>");
									w.close();
									logger.debug("MD5 checksum successfully saved in file named: " + name
											+ " with following data: " + out);
								} catch (Exception ex) {
									throw new DepositException(ex);
								}
							}
						}
					}
				}
			}
		} catch (Exception e) {
			throw new DepositException("Connecting to Fedora Commons failed!", e);
		}
		return res;
	}
}
