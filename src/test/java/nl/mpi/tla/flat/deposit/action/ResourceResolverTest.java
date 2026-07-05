/*
 * Copyright (C) 2015-2017 The Language Archive
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package nl.mpi.tla.flat.deposit.action;

import java.io.File;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import net.sf.saxon.lib.ResourceRequest;
import net.sf.saxon.lib.ResourceResolver;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

public class ResourceResolverTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void aclResolverReturnsConfiguredStylesheet() throws Exception {
        File stylesheet = temporaryFolder.newFile("acl.xsl");
        ResourceRequest request = request("jar:acl2xacml.xsl");

        Source result = new ACL.JarResourceResolver(null, stylesheet).resolve(request);

        assertEquals(stylesheet.toURI().toString(), result.getSystemId());
    }

    @Test
    public void aclResolverReturnsBundledStylesheetByDefault() throws Exception {
        Source result = new ACL.JarResourceResolver(null).resolve(request("jar:acl2xacml.xsl"));

        assertNotNull(result);
        assertNotNull(result.getSystemId());
    }

    @Test
    public void foxResolverReturnsConfiguredStylesheet() throws Exception {
        File stylesheet = temporaryFolder.newFile("fox.xsl");
        ResourceRequest request = request("jar:cmd2fox.xsl");

        Source result = new FOXCreate.JarResourceResolver(null, stylesheet).resolve(request);

        assertEquals(stylesheet.toURI().toString(), result.getSystemId());
    }

    @Test
    public void resolversDelegateOtherRequests() throws Exception {
        Source expected = new StreamSource();
        ResourceResolver fallback = request -> expected;
        ResourceRequest request = request("other.xsl");

        assertSame(expected, new ACL.JarResourceResolver(fallback).resolve(request));
        assertSame(expected, new FOXCreate.JarResourceResolver(fallback, null).resolve(request));
    }

    private static ResourceRequest request(String relativeUri) {
        ResourceRequest request = new ResourceRequest();
        request.relativeUri = relativeUri;
        request.baseUri = "file:/base.xsl";
        return request;
    }
}
