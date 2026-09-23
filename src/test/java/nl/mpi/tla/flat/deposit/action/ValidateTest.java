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
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.dom.DOMSource;
import nl.mpi.tla.schemanon.SchemAnon;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertFalse;

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

            int initialIncludes = 0;
            for (int i = 0; i < 2; i++) {
                SchemAnon validator = Validate.cachedValidator(url, temporaryFolder.getRoot(), false);
                assertTrue(validator.validate(new DOMSource(document)));
                validator.getMessages();
                if (i == 0) initialIncludes = includeDownloads.get();
            }

            assertEquals(1, mainDownloads.get());
            assertTrue("Relative include should resolve against the original URL", initialIncludes > 0);
            assertEquals("Warm validation should not fetch includes again", initialIncludes, includeDownloads.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void deploymentRulesReuseCompilationAndClearPreviousFailures() throws Exception {
        var rules = temporaryFolder.newFile("rules.sch").toPath();
        Files.writeString(rules, "<schema xmlns='http://purl.oclc.org/dsdl/schematron' queryBinding='xslt2'>"
                + "<pattern><rule context='root'><assert test=\"@ok = 'yes'\">Expected yes</assert>"
                + "</rule></pattern></schema>");
        URL url = rules.toUri().toURL();
        var validator = Validate.cachedValidator(url, temporaryFolder.getRoot(), true);
        Validate action = new Validate();
        assertFalse(runValidation(action, url, false));
        // A compiled deployment ruleset remains usable without re-reading it.
        Files.delete(rules);
        assertSame(validator, Validate.cachedValidator(url, temporaryFolder.getRoot(), true));
        assertTrue(runValidation(action, url, true));
        assertTrue("Previous errors must not leak into the next validation", validator.getMessages().isEmpty());

        var executor = Executors.newFixedThreadPool(4);
        try {
            var results = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 16; i++) {
                final boolean valid = i % 2 == 0;
                results.add(executor.submit(() -> runValidation(new Validate(), url, valid) == valid));
            }
            for (Future<Boolean> result : results)
                assertTrue("Concurrent validations must retain their own result", result.get());
        } finally {
            executor.shutdownNow();
        }
    }

    private boolean runValidation(Validate action, URL rules, boolean valid) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        var document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(
                ("<root ok='" + (valid ? "yes" : "no") + "'/>").getBytes(StandardCharsets.UTF_8)));
        return action.validateCached(rules, temporaryFolder.getRoot(), true, new DOMSource(document), document);
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (var stream = exchange.getResponseBody()) {
            stream.write(bytes);
        }
    }
}
