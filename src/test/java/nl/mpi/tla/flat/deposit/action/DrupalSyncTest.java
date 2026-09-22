package nl.mpi.tla.flat.deposit.action;

import java.io.StringReader;
import javax.xml.transform.stream.StreamSource;
import net.sf.saxon.s9api.XdmNode;
import nl.mpi.tla.util.Saxon;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class DrupalSyncTest {

    private static class TestableDrupalSync extends DrupalSync {
        boolean collection(XdmNode cmd) throws Exception {
            collectionProfiles.add("clarin.eu:cr1:p_collection");
            return isCollectionProfile(cmdiProfile(cmd));
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
}
