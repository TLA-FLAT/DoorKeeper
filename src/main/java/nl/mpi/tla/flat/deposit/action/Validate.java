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
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.XMLConstants;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamSource;
import javax.xml.transform.stream.StreamResult;

import nl.mpi.tla.schemanon.Message;
import nl.mpi.tla.schemanon.SchemAnon;
import nl.mpi.tla.schemanon.SchemAnonException;
import nl.mpi.tla.util.Saxon;

import nl.mpi.tla.flat.deposit.Context;
import nl.mpi.tla.flat.deposit.DepositException;
import nl.mpi.tla.flat.deposit.UserLog;
import static nl.mpi.tla.flat.deposit.util.Global.NAMESPACES;
import net.sf.saxon.s9api.XdmItem;
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
    private static final Duration SCHEMA_CACHE_TTL = Duration.ofHours(24);
    private static final int MAX_SCHEMA_BYTES = 10 * 1024 * 1024;
    // SchemAnon caches compilation, but also holds mutable validation results.
    // Bound retained profiles and lock each instance through result processing.
    private static final int MAX_VALIDATORS = 64;
    private static final Map<ValidatorKey, CachedValidator> VALIDATORS = new LinkedHashMap<>(16, 0.75f, true);
    private record ValidatorKey(String url, String cacheDirectory, boolean deploymentRules) { }
    private record CachedValidator(SchemAnon validator, long createdNanos) { }
    private static final Pattern ENUMERATION_ERROR = Pattern.compile(
            "Value '([^']*)' is not facet-valid with respect to enumeration '\\[([^]]*)\\]'.*",
            Pattern.DOTALL);
    private static final Pattern PATTERN_ERROR = Pattern.compile(
            "Value '([^']*)' is not facet-valid with respect to pattern '([^']*)' for type '([^']*)'.*",
            Pattern.DOTALL);
    private static final Pattern BOUND_ERROR = Pattern.compile(
            "Value '([^']*)' is not facet-valid with respect to (minInclusive|maxInclusive|minExclusive|maxExclusive) '([^']*)'.*",
            Pattern.DOTALL);
    private static final Pattern LENGTH_ERROR = Pattern.compile(
            "Value '([^']*)' with length = '[^']*' is not facet-valid with respect to (minLength|maxLength|length) '([^']*)'.*",
            Pattern.DOTALL);
    private static final Pattern ELEMENT_IN_MESSAGE = Pattern.compile("Element '([^']+)'", Pattern.CASE_INSENSITIVE);
    private static final Pattern SIMPLE_TYPE_IN_MESSAGE = Pattern.compile(
            "type 'simpletype-([A-Za-z][A-Za-z0-9_-]*?)(?:-\\d+)?---'", Pattern.CASE_INSENSITIVE);
    private static final Pattern VALUE_IN_MESSAGE = Pattern.compile("Value '([^']*)'");

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
            Document validationDocument = reparseNamespaceAware(rec);
            Source doc = new DOMSource(validationDocument);

            // the CMD XSD may carry embedded Schematron rules (validated as a side
            // effect of validating against it), on top of the separately configured rules
            boolean valid = validateCached(URI.create(xsd).toURL(), cache, false, doc, validationDocument);
            if (rules != null && !rules.isEmpty())
                valid = validateCached(Paths.get(rules).toUri().toURL(), cache, true, doc, validationDocument) && valid;

            if (valid)
                UserLog.metadataInfo("The metadata passed validation.");

            return valid;
        } catch (DepositException ex) {
            UserLog.metadataError("The metadata could not be validated: " + userText(ex.getMessage()));
            throw ex;
        } catch (Exception ex) {
            UserLog.metadataError("The metadata could not be validated. Please check the metadata profile and values.");
            throw new DepositException(ex);
        }
    }

    /** Deployment rules reload on restart; profile schemas retain the 24h refresh policy. */
    boolean validateCached(URL url, File cache, boolean deploymentRules, Source document,
            Document validationDocument) throws Exception {
        SchemAnon validator = cachedValidator(url, cache, deploymentRules);
        synchronized (validator) {
            // Include getMessages() in the lock: it consumes the current report.
            return validate(validator, document, validationDocument);
        }
    }

    static SchemAnon cachedValidator(URL url, File cache, boolean deploymentRules) throws Exception {
        ValidatorKey key = new ValidatorKey(url.toExternalForm(), cache.getCanonicalPath(), deploymentRules);
        synchronized (VALIDATORS) {
            CachedValidator existing = VALIDATORS.get(key);
            if (existing != null && (deploymentRules
                    || System.nanoTime() - existing.createdNanos() < SCHEMA_CACHE_TTL.toNanos())) {
                return existing.validator();
            }
        }
        // Fetch/parse outside the map lock so another profile is never held up
        // by network I/O. Only the winning instance is subsequently compiled.
        SchemAnon created = new SchemAnon(deploymentRules
                ? new StreamSource(url.toExternalForm()) : cachedSchemaSource(url, cache));
        synchronized (VALIDATORS) {
            CachedValidator existing = VALIDATORS.get(key);
            if (existing != null && (deploymentRules
                    || System.nanoTime() - existing.createdNanos() < SCHEMA_CACHE_TTL.toNanos())) {
                return existing.validator();
            }
            VALIDATORS.put(key, new CachedValidator(created, System.nanoTime()));
            while (VALIDATORS.size() > MAX_VALIDATORS) {
                VALIDATORS.remove(VALIDATORS.keySet().iterator().next());
            }
            return created;
        }
    }

    /**
     * Cache the top-level remote XSD while retaining its original URL as the
     * base URI, so relative imports continue to resolve against the registry.
     * Each SchemAnon instance gets a fresh DOMSource because it reads the
     * schema more than once during type detection and validation.
     */
    static Source cachedSchemaSource(URL url, File cacheDir) throws Exception {
        String protocol = url.getProtocol().toLowerCase(Locale.ROOT);
        if (!protocol.equals("http") && !protocol.equals("https"))
            return new StreamSource(url.toExternalForm());

        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(url.toExternalForm().getBytes(StandardCharsets.UTF_8)));
        Path cacheFile = cacheDir.toPath().resolve(hash + ".xsd");
        boolean hit = Files.isRegularFile(cacheFile)
                && Files.size(cacheFile) > 0
                && Files.size(cacheFile) <= MAX_SCHEMA_BYTES
                && Files.getLastModifiedTime(cacheFile).toInstant()
                        .isAfter(Instant.now().minus(SCHEMA_CACHE_TTL));

        byte[] bytes = hit ? Files.readAllBytes(cacheFile) : downloadSchema(url);

        Document schema;
        try {
            schema = parseSchema(bytes, url);
        } catch (Exception ex) {
            if (!hit)
                throw ex;
            logger.warn("Cached CMDI schema is invalid; downloading it again", ex);
            bytes = downloadSchema(url);
            schema = parseSchema(bytes, url);
            hit = false;
        }

        if (!hit) {
            try {
                writeSchemaCache(cacheFile, bytes);
            } catch (Exception ex) {
                // A writable cache is an optimization, not a prerequisite for
                // validating a schema that has already been downloaded.
                logger.warn("Couldn't write CMDI schema cache file [{}]", cacheFile, ex);
            }
        }
        return new DOMSource(schema, url.toExternalForm());
    }

    private static byte[] downloadSchema(URL url) throws Exception {
        URLConnection connection = url.openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(20000);
        try (InputStream input = connection.getInputStream()) {
            byte[] bytes = input.readNBytes(MAX_SCHEMA_BYTES + 1);
            if (bytes.length > MAX_SCHEMA_BYTES)
                throw new java.io.IOException("CMDI schema exceeds the 10 MiB download limit: " + url);
            return bytes;
        }
    }

    private static Document parseSchema(byte[] bytes, URL url) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes), url.toExternalForm());
    }

    private static void writeSchemaCache(Path cacheFile, byte[] bytes) throws Exception {
        Path temporary = Files.createTempFile(cacheFile.getParent(), "schema-", ".tmp");
        try {
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, cacheFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporary, cacheFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
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
    protected boolean validate(SchemAnon validator, Source doc, Document validationDocument) throws SchemAnonException, java.io.IOException {
        boolean valid = validator.validate(doc);
        Set<String> fieldsWithSpecificErrors = new HashSet<>();
        for (Message msg : validator.getMessages()) {
            String at = (msg.getLocation() != null ? " at ["+msg.getLocation()+"]" : "");
            if (msg.isError()) {
                logger.error("["+validator.getType()+"]"+at+": "+msg.getText());
                String field = fieldFromMessage(msg.getText());
                if (!isCascadingValueError(msg.getText()) || field.isBlank()
                        || !fieldsWithSpecificErrors.contains(field)) {
                    UserLog.metadataError(userMessage(msg, validationDocument));
                }
                if (!field.isBlank() && !isCascadingValueError(msg.getText()))
                    fieldsWithSpecificErrors.add(field);
            }
            else {
                logger.warn("["+validator.getType()+"]"+at+": "+msg.getText());
                UserLog.metadataWarning(userMessage(msg, validationDocument));
            }
        }
        return valid;
    }

    private String userMessage(Message message, Document document) {
        String location = message.getLocation();
        String path = "";
        String value = "";
        if (location != null && location.stripLeading().startsWith("/")) {
            try {
                XdmItem item = Saxon.xpathSingle(Saxon.wrapNode(document), location);
                if (item != null) {
                    path = Saxon.xpath2string(item,
                            "string-join(for $n in ancestor-or-self::* return local-name($n), ' > ')");
                    path = path.replaceFirst("^CMD( > Components)? > ?", "");
                    if (Saxon.xpath2boolean(item, "empty(*)"))
                        value = Saxon.xpath2string(item, "normalize-space(.)");
                }
            } catch (Exception ex) {
                logger.debug("Couldn't resolve validation location [{}] for the user log", location, ex);
            }
        }

        if (path.isBlank())
            path = fieldFromMessage(message.getText());

        Matcher valueMatcher = VALUE_IN_MESSAGE.matcher(message.getText());
        if (value.isBlank() && valueMatcher.find())
            value = valueMatcher.group(1);

        StringBuilder result = new StringBuilder("Metadata problem");
        if (!path.isBlank())
            result.append(" in ").append(path);
        if (!value.isBlank() && value.length() <= 160)
            result.append(" (value: \"").append(value).append("\")");
        result.append(": ").append(userText(message.getText()));
        return result.toString();
    }

    static String userText(String text) {
        if (text == null || text.isBlank())
            return "The value does not satisfy the metadata profile.";
        String normalized = text.replaceAll("\\s+", " ").trim();
        boolean schemaValidatorMessage = normalized.startsWith("cvc-");
        normalized = normalized.replaceFirst("^cvc-[^:]+:\\s*", "");
        Matcher enumeration = ENUMERATION_ERROR.matcher(normalized);
        if (enumeration.matches())
            return "Choose one of these allowed values: " + enumeration.group(2) + ".";
        Matcher pattern = PATTERN_ERROR.matcher(normalized);
        if (pattern.matches()) {
            if (isDateConstraint(pattern.group(2), pattern.group(3)))
                return "Use YYYY, YYYY-MM, or YYYY-MM-DD; for a range use two such dates separated by '/', or enter Unknown or Unspecified.";
            return "Use the format required for this field.";
        }
        Matcher bound = BOUND_ERROR.matcher(normalized);
        if (bound.matches())
            return boundMessage(bound.group(2), bound.group(3));
        Matcher length = LENGTH_ERROR.matcher(normalized);
        if (length.matches())
            return lengthMessage(length.group(2), length.group(3));
        if (isCascadingValueError(normalized))
            return "Enter a valid value for this field.";
        if (normalized.contains("is not a valid value for"))
            return "Enter a value of the required type.";
        if (normalized.contains("Attribute '") && normalized.contains("must appear"))
            return "A required value is missing.";
        if (normalized.contains("The content of element") && normalized.contains("is not complete"))
            return "A required metadata field is missing.";
        if (normalized.contains("Invalid content was found starting with element"))
            return "This field is not allowed here, or another required field must come before it.";
        if (schemaValidatorMessage)
            return "The value does not satisfy the requirements for this field.";
        return normalized;
    }

    static String fieldFromMessage(String text) {
        if (text == null)
            return "";
        Matcher element = ELEMENT_IN_MESSAGE.matcher(text);
        if (element.find())
            return localName(element.group(1));
        Matcher type = SIMPLE_TYPE_IN_MESSAGE.matcher(text);
        if (type.find())
            return type.group(1).replace('-', ' ');
        return "";
    }

    static boolean isCascadingValueError(String text) {
        return text != null && text.contains("must have no element [children], and the value must be valid");
    }

    private static String localName(String qualifiedName) {
        int colon = qualifiedName.indexOf(':');
        return colon >= 0 ? qualifiedName.substring(colon + 1) : qualifiedName;
    }

    private static boolean isDateConstraint(String pattern, String type) {
        return type.toLowerCase().contains("date")
                || (pattern.contains("[0-9]{4}") && pattern.contains("Unknown")
                        && pattern.contains("Unspecified"));
    }

    private static String boundMessage(String constraint, String limit) {
        return switch (constraint) {
            case "minInclusive" -> "Enter a value of at least " + limit + ".";
            case "maxInclusive" -> "Enter a value no greater than " + limit + ".";
            case "minExclusive" -> "Enter a value greater than " + limit + ".";
            case "maxExclusive" -> "Enter a value less than " + limit + ".";
            default -> "Enter a value within the allowed range.";
        };
    }

    private static String lengthMessage(String constraint, String limit) {
        return switch (constraint) {
            case "minLength" -> "Enter at least " + limit + " characters.";
            case "maxLength" -> "Enter no more than " + limit + " characters.";
            case "length" -> "Enter exactly " + limit + " characters.";
            default -> "Enter a value of the required length.";
        };
    }
}
