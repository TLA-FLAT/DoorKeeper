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
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.Scanner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.sf.saxon.s9api.XdmItem;
import nl.mpi.tla.flat.deposit.Context;
import nl.mpi.tla.flat.deposit.DepositException;
import nl.mpi.tla.flat.deposit.sip.Collection;
import nl.mpi.tla.flat.deposit.sip.Resource;
import nl.mpi.tla.flat.deposit.sip.SIPInterface;
import nl.mpi.tla.flat.deposit.util.Saxon;

/**
 *
 * @author menzowi
 * @author pavi
 */
public class Index extends AbstractAction {

	private static final Logger logger = LoggerFactory.getLogger(Index.class.getName());
	private static final String MEMO = "nl.mpi.tla.flat.deposit.action.Index.INDEXED";

	@Override
	public boolean perform(Context context) throws DepositException {

		String gsearchService = getParameter("gsearchServer");
		if (!gsearchService.endsWith("/")) {
			gsearchService += "/";
		}
		final String gsearchUser = getParameter("gsearchUser");
		final String gsearchPass = getParameter("gsearchPassword");

		SIPInterface sip = context.getSIP();

		try {
			URL gsearchEndpoint = new URL(gsearchService);

			Authenticator.setDefault(new Authenticator() {
				protected PasswordAuthentication getPasswordAuthentication() {
					return new PasswordAuthentication(gsearchUser, gsearchPass.toCharArray());
				}
			});

			context.registerRollbackEvent(this, "index", "fid", sip.getFID(true).toString());
			URL call = new URL(gsearchEndpoint, "rest?operation=updateIndex&action=fromPid&value="
					+ URLEncoder.encode(sip.getFID(true).toString(), "UTF-8"));

			InputStream response = call.openStream();
			try (Scanner scanner = new Scanner(response)) {
				String responseBody = scanner.useDelimiter("\\A").next();
				// System.err.println(responseBody);
			}

			for (Resource res : sip.getResources()) {
				context.registerRollbackEvent(this, "index", "fid", res.getFID(true).toString());
				call = new URL(gsearchEndpoint, "rest?operation=updateIndex&action=fromPid&value="
						+ URLEncoder.encode(res.getFID(true).toString(), "UTF-8"));

				response = call.openStream();
				try (Scanner scanner = new Scanner(response)) {
					String responseBody = scanner.useDelimiter("\\A").next();
					// System.err.println(responseBody);
				}
			}

			for (Collection col : sip.getCollections(true)) {
				context.registerRollbackEvent(this, "index", "fid", col.getFID(true).toString());
				call = new URL(gsearchEndpoint, "rest?operation=updateIndex&action=fromPid&value="
						+ URLEncoder.encode(col.getFID(true).toString(), "UTF-8"));

				response = call.openStream();
				try (Scanner scanner = new Scanner(response)) {
					String responseBody = scanner.useDelimiter("\\A").next();
					// System.err.println(responseBody);
				}
			}
		} catch (Exception ex) {
			throw new DepositException(ex);
		}

		return true;
	}

	public void rollback(Context context, List<XdmItem> events) {
		if (events.size() > 0) {
			List<String> indexedFids = new ArrayList<String>();
			for (ListIterator<XdmItem> iter = events.listIterator(events.size()); iter.hasPrevious();) {
				XdmItem event = iter.previous();
				try {
					String tpe = Saxon.xpath2string(event, "@type");
					if (tpe.equals("index")) {
						String fid = Saxon.xpath2string(event, "param[@name='fid']/@value");
						indexedFids.add(fid);
					}
				} catch (Exception ex) {
					logger.error("rollback action[" + this.getName() + "] event[" + event + "] failed!", ex);
				}
			}
			context.putInMemory(MEMO, indexedFids);
		}
	}
}