/*
 * Copyright (C) 2015-2017 The Language Archive
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package nl.mpi.tla.flat.deposit.action;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.dom.DOMSource;
import nl.mpi.tla.schemanon.SchemAnon;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ValidateTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private static final String DATE_ERROR = "cvc-pattern-valid: Value '20267' is not facet-valid "
            + "with respect to pattern '[0-9]{4}(-(0[1-9]|1[012])(-([0-2][0-9]|3[01]))?)?"
            + "(/[0-9]{4}(-(0[1-9]|1[012])(-([0-2][0-9]|3[01]))?)?)?|Unknown|Unspecified' "
            + "for type 'simpletype-Date-1---'.";

    @Test
    public void datePatternErrorIsExplainedWithoutSchemaSyntax() {
        assertEquals(
                "Use YYYY, YYYY-MM, or YYYY-MM-DD; for a range use two such dates separated by '/', "
                        + "or enter Unknown or Unspecified.",
                Validate.userText(DATE_ERROR));
        assertEquals("Date", Validate.fieldFromMessage(DATE_ERROR));
    }

    @Test
    public void genericPatternAndEnumerationErrorsArePlainLanguage() {
        assertEquals("Use the format required for this field.", Validate.userText(
                "cvc-pattern-valid: Value 'abc' is not facet-valid with respect to pattern '[A-Z]+' "
                        + "for type 'simpletype-Code-1---'."));
        assertEquals("Choose one of these allowed values: speaker, interviewer.", Validate.userText(
                "cvc-enumeration-valid: Value 'other' is not facet-valid with respect to enumeration "
                        + "'[speaker, interviewer]'. It must be a value from the enumeration."));
    }

    @Test
    public void derivativeElementErrorCanBeSuppressedForTheSameField() {
        String error = "cvc-complex-type.2.2: Element 'cmd:Date' must have no element [children], "
                + "and the value must be valid.";

        assertTrue(Validate.isCascadingValueError(error));
        assertEquals("Date", Validate.fieldFromMessage(error));
        assertEquals("Enter a valid value for this field.", Validate.userText(error));
    }

    @Test
    public void unknownSchemaDiagnosticDoesNotLeakValidatorJargon() {
        assertEquals("The value does not satisfy the requirements for this field.",
                Validate.userText("cvc-something-new: Internal schema terminology."));
    }

    @Test
    public void cachedSchemaRetainsBaseUrlForRelativeIncludes() throws Exception {
        AtomicInteger mainDownloads = new AtomicInteger();
        AtomicInteger includeDownloads = new AtomicInteger();
        String main = "<xs:schema xmlns:xs='http://www.w3.org/2001/XMLSchema' "
                + "xmlns:t='urn:cache-test' targetNamespace='urn:cache-test'>"
                + "<xs:include schemaLocation='child.xsd'/>"
                + "<xs:element name='root' type='t:ValueType'/></xs:schema>";
        String child = "<xs:schema xmlns:xs='http://www.w3.org/2001/XMLSchema' "
                + "targetNamespace='urn:cache-test'><xs:simpleType name='ValueType'>"
                + "<xs:restriction base='xs:string'/></xs:simpleType></xs:schema>";
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/schema/main.xsd", exchange -> {
            mainDownloads.incrementAndGet();
            respond(exchange, main);
        });
        server.createContext("/schema/child.xsd", exchange -> {
            includeDownloads.incrementAndGet();
            respond(exchange, child);
        });
        server.start();
        try {
            URL url = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/schema/main.xsd").toURL();
            String xml = "<t:root xmlns:t='urn:cache-test'>test</t:root>";
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            var document = factory.newDocumentBuilder().parse(
                    new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

            for (int i = 0; i < 2; i++) {
                SchemAnon validator = new SchemAnon(Validate.cachedSchemaSource(url, temporaryFolder.getRoot()));
                assertTrue(validator.validate(new DOMSource(document)));
            }

            assertEquals(1, mainDownloads.get());
            assertTrue("Relative include should resolve against the original URL", includeDownloads.get() > 0);
        } finally {
            server.stop(0);
        }
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (var stream = exchange.getResponseBody()) {
            stream.write(bytes);
        }
    }
}
