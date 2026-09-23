package nl.mpi.tla.flat.deposit.action;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.StringReader;
import javax.xml.transform.stream.StreamSource;
import net.sf.saxon.s9api.XdmNode;
import nl.mpi.tla.flat.deposit.DepositException;
import nl.mpi.tla.util.Saxon;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class DrupalSyncTest {

    private static class TestableDrupalSync extends DrupalSync {
        boolean collection(XdmNode cmd) throws Exception {
            collectionProfiles.add("clarin.eu:cr1:p_collection");
            return isCollectionProfile(cmdiProfile(cmd));
        }
    }

    private static class CachingDrupalSync extends DrupalSync {
        int lookups;

        CachingDrupalSync(String server) {
            this.server = server;
        }

        TermRef lookup(String vocabulary, String name) throws DepositException {
            return termId(vocabulary, name);
        }

        @Override
        protected JsonNode jsonapiOne(String resource, String query, String resourceType, String fields) {
            lookups++;
            if (query.contains("missing"))
                return null;
            if (query.contains("malformed"))
                return MAPPER.createObjectNode();
            return MAPPER.createObjectNode()
                    .put("id", "term-uuid")
                    .set("attributes", MAPPER.createObjectNode().put("drupal_internal__tid", "42"));
        }
    }

    private static XdmNode cmd(String profile) throws Exception {
        String xml = "<cmd:CMD xmlns:cmd='http://www.clarin.eu/cmd/'>"
                + "<cmd:Header><cmd:MdProfile>" + profile + "</cmd:MdProfile>"
                + "</cmd:Header><cmd:Components><cmd:MPI_Collection><cmd:Title>Title"
                + "</cmd:Title></cmd:MPI_Collection></cmd:Components></cmd:CMD>";
        return Saxon.buildDocument(new StreamSource(new StringReader(xml)));
    }

    @Test
    public void collectionProfileUsesCollectionModelClassification() throws Exception {
        TestableDrupalSync sync = new TestableDrupalSync();
        assertTrue(sync.collection(cmd("clarin.eu:cr1:p_collection")));
        assertFalse(sync.collection(cmd("clarin.eu:cr1:p_bundle")));
    }

    @Test
    public void sparseFieldsPreserveExistingQueryAndLimitResponseFields() {
        assertEquals(
                "http://drupal/jsonapi/node/islandora_object?filter[field_fid]=lat_1"
                + "&fields[node--islandora_object]=drupal_internal__nid",
                DrupalSync.sparseFields(
                        "http://drupal/jsonapi/node/islandora_object?filter[field_fid]=lat_1",
                        "node--islandora_object", "drupal_internal__nid"));
    }

    @Test
    public void taxonomyTermsAreCachedAcrossIngestInstancesButNotServers() throws Exception {
        CachingDrupalSync first = new CachingDrupalSync("http://drupal-cache-test-a");
        CachingDrupalSync second = new CachingDrupalSync("http://drupal-cache-test-a");
        CachingDrupalSync otherServer = new CachingDrupalSync("http://drupal-cache-test-b");

        assertEquals("42", first.lookup("islandora_models", "cache-test-model").tid());
        assertEquals("term-uuid", second.lookup("islandora_models", "cache-test-model").uuid());
        assertEquals("42", otherServer.lookup("islandora_models", "cache-test-model").tid());
        assertEquals(1, first.lookups);
        assertEquals(0, second.lookups);
        assertEquals(1, otherServer.lookups);
    }

    @Test
    public void missingTaxonomyTermsAreNotCached() {
        CachingDrupalSync sync = new CachingDrupalSync("http://drupal-cache-test-missing");

        assertThrows(DepositException.class, () -> sync.lookup("islandora_models", "missing"));
        assertThrows(DepositException.class, () -> sync.lookup("islandora_models", "missing"));
        assertEquals(2, sync.lookups);
    }

    @Test
    public void malformedTaxonomyTermsAreNotCached() {
        CachingDrupalSync sync = new CachingDrupalSync("http://drupal-cache-test-malformed");

        assertThrows(DepositException.class, () -> sync.lookup("islandora_models", "malformed"));
        assertThrows(DepositException.class, () -> sync.lookup("islandora_models", "malformed"));
        assertEquals(2, sync.lookups);
    }
}
