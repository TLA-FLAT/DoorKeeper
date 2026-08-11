package nl.mpi.tla.flat.deposit.action;

import java.net.URI;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class FedoraHierarchyCompatibilityTest {

    private static class TestableFedoraInteract extends FedoraInteract {
        String sparqlValue(String value) {
            return toSPARQL_URI(value);
        }
    }

    @Test
    public void infoFedoraRelationshipIsWrittenAsResource() {
        TestableFedoraInteract interact = new TestableFedoraInteract();
        assertEquals("<info:fedora/lat_12345_parent>", interact.sparqlValue("info:fedora/lat_12345_parent"));
        assertEquals("'plain text'", interact.sparqlValue("plain text"));
    }

    @Test
    public void collectionFidNormalizesCurrentAndLegacyRelationshipValues() throws Exception {
        String localBase = "http://fcrepo:8080/fcrepo/rest";
        String localServer = "http://fcrepo:8080/fcrepo/rest";
        URI expected = new URI("lat_12345_parent");

        assertEquals(expected, FedoraLoadCollectionHierarchy.normalizeCollectionFID(
            "info:fedora/lat_12345_parent", localBase, localServer));
        assertEquals(expected, FedoraLoadCollectionHierarchy.normalizeCollectionFID(
            localBase + "/lat_12345_parent#fragment", localBase, localServer));
    }
}
