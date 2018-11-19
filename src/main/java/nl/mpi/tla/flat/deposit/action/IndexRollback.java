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

import java.io.InputStream;
import java.net.Authenticator;
import java.net.PasswordAuthentication;
import java.net.URL;
import java.net.URLEncoder;
import java.util.List;
import java.util.Scanner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.sf.saxon.s9api.XdmItem;
import nl.mpi.tla.flat.deposit.Context;
import nl.mpi.tla.flat.deposit.DepositException;
import nl.mpi.tla.flat.deposit.sip.SIPInterface;

/**
 *
 * @author pavi
 */
public class IndexRollback extends AbstractAction {

	private static final String MEMO = "nl.mpi.tla.flat.deposit.action.Index.INDEXED";
	private static final Logger logger = LoggerFactory.getLogger(IndexRollback.class.getName());

	@Override
	public boolean perform(Context context) throws DepositException {
		return true;
	}

	@Override
	public void rollback(Context context, List<XdmItem> events) {
		if (context.hasInMemory(MEMO)) {
			List indexedFids = (List) context.getFromMemory(MEMO);
			if (!indexedFids.isEmpty()) {
				String gsearchService;
				URL gsearchEndpoint = null;
				SIPInterface sip = null;
				try {
					gsearchService = getParameter("gsearchServer");
					if (!gsearchService.endsWith("/")) {
						gsearchService += "/";
					}
					final String gsearchUser = getParameter("gsearchUser");
					final String gsearchPass = getParameter("gsearchPassword");

					sip = context.getSIP();

					gsearchEndpoint = new URL(gsearchService);

					Authenticator.setDefault(new Authenticator() {
						protected PasswordAuthentication getPasswordAuthentication() {
							return new PasswordAuthentication(gsearchUser, gsearchPass.toCharArray());
						}
					});

					for (int i = 0; i < indexedFids.size(); i++) {
						try {
							URL call = new URL(gsearchEndpoint, "rest?operation=updateIndex&action=fromPid&value="
									+ URLEncoder.encode(sip.getFID(true).toString(), "UTF-8"));

							InputStream response = call.openStream();
							try (Scanner scanner = new Scanner(response)) {
								String responseBody = scanner.useDelimiter("\\A").next();
							}
						} catch (Exception ex) {
							logger.error("rollback action[" + this.getName() + " failed!", ex);
						}
					}
				} catch (Exception ex) {
					logger.error("rollback action[" + this.getName() + " failed!", ex);
				}
			}

		}
	}
}
