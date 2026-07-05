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


import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.URI;
import java.nio.file.Paths;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import nl.mpi.tla.schemanon.Message;
import nl.mpi.tla.schemanon.SchemAnon;
import nl.mpi.tla.schemanon.SchemAnonException;
import nl.mpi.tla.util.Saxon;

import nl.mpi.tla.flat.deposit.Context;
import nl.mpi.tla.flat.deposit.DepositException;
import static nl.mpi.tla.flat.deposit.util.Global.NAMESPACES;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;

/**
 *
 * @author menzowi
 */
public class Validate extends AbstractAction {

    private static final Logger logger = LoggerFactory.getLogger(Validate.class.getName());

    @Override
    public boolean perform(Context context) throws DepositException {
        try {
            String schemaCache = getParameter("schemaCache","./cache");
            String rules = getParameter("rules");

            File cache = new File(schemaCache);
            if (!cache.exists())
                 FileUtils.forceMkdir(cache);

            Document rec = context.getSIP().getRecord();
            String xsd = Saxon.xpath2string(Saxon.wrapNode(rec), "/*/@xsi:schemaLocation", null, NAMESPACES).replaceAll(".* ","");
            logger.debug("XSD schema location["+xsd+"]");
            if (xsd.isEmpty())
                throw new DepositException("The SIP document doesn't specify a @xsi:schemaLocation!");

            // Re-serialize and re-parse namespace-aware: the in-memory record DOM is
            // built by a non-namespace-aware DocumentBuilder (SaxonUtils'
            // Saxon.buildDOM()), so its elements report a null namespaceURI even
            // though Saxon's own XPath handling tolerates it. Xerces' schema
            // validator does not, and fails to find any element declaration
            // (cvc-elt.1.a) when handed that DOM directly via DOMSource. A DOMSource
            // (rather than a stream) is required here since SchemAnon reads it twice:
            // once for XSD, once for the XSD's embedded Schematron rules.
            Source doc = new DOMSource(reparseNamespaceAware(rec));

            // the CMD XSD may carry embedded Schematron rules (validated as a side
            // effect of validating against it), on top of the separately configured rules
            boolean valid = validate(new SchemAnon(URI.create(xsd).toURL()), doc);
            if (rules != null && !rules.isEmpty())
                valid = validate(new SchemAnon(Paths.get(rules).toUri().toURL()), doc) && valid;

            return valid;
        } catch (DepositException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new DepositException(ex);
        }
    }

    private static Document reparseNamespaceAware(Document doc) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.transform(new DOMSource(doc), new StreamResult(out));
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        return dbf.newDocumentBuilder().parse(new ByteArrayInputStream(out.toByteArray()));
    }

    /** Run one SchemAnon validation pass, logging every reported message. */
    protected boolean validate(SchemAnon validator, Source doc) throws SchemAnonException, java.io.IOException {
        boolean valid = validator.validate(doc);
        for (Message msg : validator.getMessages()) {
            String at = (msg.getLocation() != null ? " at ["+msg.getLocation()+"]" : "");
            if (msg.isError())
                logger.error("["+validator.getType()+"]"+at+": "+msg.getText());
            else
                logger.warn("["+validator.getType()+"]"+at+": "+msg.getText());
        }
        return valid;
    }
}
