/* 
 * Copyright (C) 2018 The Language Archive
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

import java.util.Date;
import java.util.Properties;

import javax.mail.Authenticator;
import javax.mail.MessagingException;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import javax.xml.transform.dom.DOMSource;

import java.io.File;

import javax.xml.transform.stream.StreamSource;

import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.XdmAtomicValue;
import net.sf.saxon.s9api.XdmDestination;
import net.sf.saxon.s9api.XdmNode;
import net.sf.saxon.s9api.XsltExecutable;
import net.sf.saxon.s9api.XsltTransformer;
import nl.mpi.tla.flat.deposit.Context;
import nl.mpi.tla.flat.deposit.DepositException;
import nl.mpi.tla.flat.deposit.util.Saxon;
import nl.mpi.tla.flat.deposit.util.SaxonListener;

import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.jena.ext.com.google.common.base.Throwables;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 *
 * @author pavi
 */
public class Mail extends FedoraAction {

	private static final Logger logger = LoggerFactory.getLogger(Mail.class.getName());

	@Override
	public boolean perform(Context context) throws DepositException {
		try {

			File mailConfig = new File(getParameter("config"));
			if (!mailConfig.exists()) {
				logger.error("The Mail configuration[" + mailConfig + "] doesn't exist!");
				return false;
			} else if (!mailConfig.isFile()) {
				logger.error("The Mail configuration[" + mailConfig + "] isn't a file!");
				return false;
			} else if (!mailConfig.canRead()) {
				logger.error("The Mail configuration[" + mailConfig + "] can't be read!");
				return false;
			}
			logger.debug("Mail configuration[" + mailConfig.getAbsolutePath() + "]");

			XdmNode mailNodeConfig = Saxon.buildDocument(new StreamSource(mailConfig));
			XdmNode mailNode = (XdmNode) Saxon.xpathSingle(mailNodeConfig, "/mailConfig");

			String server = Saxon.xpath2string(mailNode, "./server");
			logger.debug("Server[" + server + "]");
			String port = Saxon.xpath2string(mailNode, "./port");
			logger.debug("Port[" + port + "]");
			final String user = Saxon.xpath2string(mailNode, "./user");
			final String pswd = Saxon.xpath2string(mailNode, "./password");
			String from = Saxon.xpath2string(mailNode, "./from");
			logger.debug("from[" + from + "]");
			String to = Saxon.xpath2string(mailNode, "./to");
			if (hasParameter("to"))
				to = getParameter("to");
			logger.debug("to[" + to + "]");

			String subject = Saxon.xpath2string(mailNode, "./subject");
			if (hasParameter("subject"))
				subject = getParameter("subject");
			logger.debug("Subject[" + subject + "]");

			File xsl = new File(getParameter("template"));
			XsltExecutable template = Saxon.buildTransformer(xsl);

			XsltTransformer fox = template.load();
			SaxonListener listener = new SaxonListener("Mail", MDC.get("sip"));
			fox.setMessageListener(listener);
			fox.setErrorListener(listener);

			for (String param : this.params.keySet()) {
				if (param.startsWith("tmpl-"))
					logger.debug("param[" + param + "]: " + params.get(param));
				fox.setParameter(new QName(param.replaceFirst("^tmpl-", "")), params.get(param));
			}

			String stackTrace;
			
			fox.setParameter(new QName("sip"), new XdmAtomicValue(context.getSIP().getBase().toURI().toString()));
			if (context.hasException()) {
				fox.setParameter(new QName("exception"), new XdmAtomicValue(context.getException().toString()));
				stackTrace = ExceptionUtils.getFullStackTrace(context.getException()) ;
				fox.setParameter(new QName("stacktrace"), new XdmAtomicValue(stackTrace));
			}

			fox.setSource(new DOMSource(context.getSIP().getRecord(), context.getSIP().getBase().toURI().toString()));
			XdmDestination destination = new XdmDestination();
			fox.setDestination(destination);
			fox.transform();

			// sets SMTP server properties
			Properties properties = new Properties();
			properties.put("mail.smtp.host", server);
			properties.put("mail.smtp.port", port);

			// creates new session for the with or without authenticator
			javax.mail.Session session;
			if (!user.isEmpty() && !pswd.isEmpty()) {
				properties.put("mail.smtp.auth", "true");
				properties.put("mail.smtp.ssl.enable", "true");
				logger.debug("Authenticator for the session required as username and password are present");
				Authenticator auth = new Authenticator() {
					@Override
					public javax.mail.PasswordAuthentication getPasswordAuthentication() {
						return new javax.mail.PasswordAuthentication(user, pswd);
					}
				};
				session = javax.mail.Session.getInstance(properties, auth);
			} else {
				properties.put("mail.smtp.auth", "false");
				logger.debug("Authenticator for the session not required");
				session = javax.mail.Session.getInstance(properties, null);
			}

			// creates a new e-mail message
			logger.debug("Create new msg");
			javax.mail.Message msg = new MimeMessage(session);
			InternetAddress fromAddress = new InternetAddress(from);
			InternetAddress toAddress = new InternetAddress(to);

			msg.setFrom(fromAddress);
			msg.addRecipient(javax.mail.Message.RecipientType.TO, toAddress);
			msg.setSubject(subject);
			msg.setSentDate(new Date());

			msg.setContent(destination.getXdmNode().toString(), "text/html");
			msg.saveChanges();

			try {
				// sends the e-mail
				logger.debug("Sending the email. . . . . . . .");
				Transport.send(msg);
				logger.info("Email was sent successfully. . . . !");
			} catch (MessagingException ex) {
				logger.error("Error while trying to send mail message to the user!!", ex);
			}

		} catch (Exception e) {
			throw new DepositException("The creation of Mail failed!", e);
		}
		return true;
	}

}