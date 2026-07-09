/*
 * Copyright (C) 2015-2017 The Language Archive
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package nl.mpi.tla.flat.deposit.action;

import java.io.StringReader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.xml.transform.stream.StreamSource;
import net.sf.saxon.s9api.XdmItem;
import net.sf.saxon.s9api.XdmNode;
import net.sf.saxon.s9api.XdmValue;
import nl.mpi.tla.flat.deposit.util.Global;
import nl.mpi.tla.util.Saxon;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class FITSTest {

    @Test
    public void detectedMimetypesUsesFitsNamespace() throws Exception {
        String xml = "<fits xmlns=\"http://hul.harvard.edu/ois/xml/ns/fits/fits_output\">"
                + "<identification>"
                + "<identity mimetype=\"text/plain\"/>"
                + "<identity mimetype=\"application/octet-stream, text/plain\"/>"
                + "</identification>"
                + "</fits>";
        XdmNode report = Saxon.buildDocument(new StreamSource(new StringReader(xml)));

        assertEquals("text/plain, application/octet-stream", FITS.detectedMimetypes(report));
    }

    @Test
    public void reportsEveryFailedAssertionForSelectedMimetype() throws Exception {
        String policyXml = "<mimetype><assertions>"
                + "<assert xpath=\"/fits:fits/fits:metadata/fits:audio/fits:sampleRate = '48000'\" "
                + "message=\"Sample rate [{/fits:fits/fits:metadata/fits:audio/fits:sampleRate}] is not accepted\"/>"
                + "<assert xpath=\"/fits:fits/fits:metadata/fits:audio/fits:bitDepth = '24'\" "
                + "message=\"Bit depth [{/fits:fits/fits:metadata/fits:audio/fits:bitDepth}] is not accepted\"/>"
                + "<assert xpath=\"/fits:fits/fits:metadata/fits:audio/fits:channels = '2'\" "
                + "message=\"Channel count is not accepted\"/>"
                + "</assertions></mimetype>";
        String reportXml = "<fits xmlns=\"http://hul.harvard.edu/ois/xml/ns/fits/fits_output\">"
                + "<metadata><audio><sampleRate>96000</sampleRate><bitDepth>32</bitDepth>"
                + "<channels>2</channels></audio></metadata></fits>";
        XdmNode policy = Saxon.buildDocument(new StreamSource(new StringReader(policyXml)));
        XdmNode report = Saxon.buildDocument(new StreamSource(new StringReader(reportXml)));
        XdmItem assertions = Saxon.xpathSingle(policy, "/mimetype/assertions", null, Global.NAMESPACES);
        Map<String, XdmValue> properties = new HashMap<>();

        List<String> failures = FITS.failedAssertionMessages(assertions, report, properties);

        assertEquals(List.of(
                "Sample rate [96000] is not accepted",
                "Bit depth [32] is not accepted"), failures);
    }
}
