package nl.mpi.tla.flat.deposit.action;

import java.io.StringReader;
import java.io.File;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.transform.stream.StreamSource;
import net.sf.saxon.s9api.XdmAtomicValue;
import net.sf.saxon.s9api.XdmItem;
import net.sf.saxon.s9api.XdmNode;
import nl.mpi.tla.flat.deposit.Context;
import nl.mpi.tla.flat.deposit.DepositException;
import nl.mpi.tla.util.Saxon;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class FedoraInteractTest {

    private static XdmNode xml(String source) throws Exception {
        return Saxon.buildDocument(new StreamSource(new StringReader(source)));
    }

    private static class TestableDrupalSync extends DrupalSync {
        int fedoraLookups;

        @Override
        protected String fetchTitle(URI fid, String fallback) {
            fedoraLookups++;
            return "Fedora title";
        }
    }

    private static class DatastreamInteract extends FedoraInteract {
        boolean updated;

        @Override
        public Boolean fcrepo_exists(URI fid, String ds, URI tx) {
            throw new AssertionError("Datastream upsert must not issue HEAD");
        }

        @Override
        void updateDatastream(Context context, File fox, String fid, String ds, String ext) throws DepositException {
            updated = true;
        }
    }

    @Test
    public void datastreamUpsertDoesNotCheckExistence() throws Exception {
        DatastreamInteract interact = new DatastreamInteract();
        interact.upsertDatastream(null, null, "lat_12345_example", "DC", "xml");
        assertTrue(interact.updated);
    }

    @Test
    public void combinesPropertyChangesInOneSparqlUpdate() throws Exception {
        FedoraInteract interact = new FedoraInteract();
        XdmNode current = xml("<rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#' "
                + "xmlns:dc='http://purl.org/dc/elements/1.1/'>"
                + "<rdf:Description><dc:title>Old title</dc:title>"
                + "<dc:identifier>md5:old</dc:identifier></rdf:Description></rdf:RDF>");
        Map<String, List<XdmItem>> updates = new LinkedHashMap<>();
        updates.put("http://purl.org/dc/elements/1.1/title", List.of(new XdmAtomicValue("New title")));
        updates.put("http://purl.org/dc/elements/1.1/identifier", List.of(new XdmAtomicValue("md5:new")));

        String patch = interact.buildPropertyPatch(current, updates);

        assertEquals(1, patch.split("DELETE \\{", -1).length - 1);
        assertEquals(1, patch.split("INSERT \\{", -1).length - 1);
        assertTrue(patch.contains("'Old title'"));
        assertTrue(patch.contains("'New title'"));
        assertTrue(patch.contains("'md5:old'"));
        assertTrue(patch.contains("'md5:new'"));
    }

    @Test
    public void combinesFoxPropertiesDcAndRelationsWithTheLastValueWinning() throws Exception {
        FedoraInteract interact = new FedoraInteract();
        XdmNode foxml = xml("<foxml:digitalObject "
                + "xmlns:foxml='info:fedora/fedora-system:def/foxml#' "
                + "xmlns:dc='http://purl.org/dc/elements/1.1/' "
                + "xmlns:relsext='info:fedora/fedora-system:def/relations-external#' "
                + "xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'>"
                + "<foxml:property NAME='http://purl.org/dc/elements/1.1/title' VALUE='Old title'/>"
                + "<dc:title>Final title</dc:title>"
                + "<relsext:isMemberOfCollection rdf:resource='info:fedora/parent'/>"
                + "</foxml:digitalObject>");
        Map<String, List<XdmItem>> updates = new LinkedHashMap<>();

        interact.collectObjectProperties(foxml, updates);
        interact.collectDC(foxml, updates);
        interact.collectRELS(foxml, updates);
        String patch = interact.buildPropertyPatch(xml("<rdf:RDF "
                + "xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'/>"), updates);

        assertEquals(2, updates.size());
        assertTrue(patch.contains("'Final title'"));
        assertTrue(patch.contains("<info:fedora/parent>"));
        assertTrue(!patch.contains("'Old title'"));
    }

    @Test
    public void drupalUsesTheTitleWrittenToDcInThisFlow() throws Exception {
        Context context = new Context(null, xml("<flow/>"),
                Map.of("dk-pidList", new XdmAtomicValue("/nonexistent-door-keeper-test-pids.csv")));
        FedoraInteract interact = new FedoraInteract();
        TestableDrupalSync sync = new TestableDrupalSync();
        URI fid = URI.create("lat_12345_example#CMD");

        interact.rememberTitle(context, "lat_12345_example", xml(
                "<dc:dc xmlns:dc='http://purl.org/dc/elements/1.1/'>"
                + "<dc:title>Written title</dc:title></dc:dc>"));

        assertEquals("Written title", sync.resolveTitle(context, fid, "fallback", null));
        assertEquals("Filename", sync.resolveTitle(context, fid, "fallback", "Filename"));
        assertEquals(0, sync.fedoraLookups);
        assertEquals("Fedora title", sync.resolveTitle(context, URI.create("lat_12345_other"), "fallback", null));
        assertEquals(1, sync.fedoraLookups);
    }
}
