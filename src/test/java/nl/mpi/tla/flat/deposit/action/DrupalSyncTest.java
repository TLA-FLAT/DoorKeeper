package nl.mpi.tla.flat.deposit.action;

import java.io.StringReader;
import javax.xml.transform.stream.StreamSource;
import net.sf.saxon.s9api.XdmNode;
import nl.mpi.tla.util.Saxon;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class DrupalSyncTest {

    private static class TestableDrupalSync extends DrupalSync {
        String title(XdmNode cmd) {
            return cmdiTitle(cmd);
        }

        boolean collection(XdmNode cmd) throws Exception {
            collectionProfiles.add("clarin.eu:cr1:p_collection");
            return isCollectionProfile(cmdiProfile(cmd));
        }
    }

    private static XdmNode cmd(String profile, String headerTitle, String componentTitle) throws Exception {
        String xml = "<cmd:CMD xmlns:cmd='http://www.clarin.eu/cmd/'>"
                + "<cmd:Header><cmd:MdProfile>" + profile + "</cmd:MdProfile>"
                + (headerTitle == null ? "" : "<cmd:MdCollectionDisplayName>" + headerTitle + "</cmd:MdCollectionDisplayName>")
                + "</cmd:Header><cmd:Components><cmd:MPI_Collection><cmd:Title>"
                + componentTitle + "</cmd:Title></cmd:MPI_Collection></cmd:Components></cmd:CMD>";
        return Saxon.buildDocument(new StreamSource(new StringReader(xml)));
    }

    @Test
    public void collectionProfileUsesCollectionModelClassification() throws Exception {
        TestableDrupalSync sync = new TestableDrupalSync();
        assertTrue(sync.collection(cmd("clarin.eu:cr1:p_collection", null, "Collection title")));
        assertFalse(sync.collection(cmd("clarin.eu:cr1:p_bundle", null, "Bundle title")));
    }

    @Test
    public void extractsUppercaseComponentTitleUsedByMpiCollection() throws Exception {
        assertEquals("DoorKeeper Test Collection", new TestableDrupalSync().title(
                cmd("clarin.eu:cr1:p_collection", null, "DoorKeeper Test Collection")));
    }

    @Test
    public void collectionDisplayNameTakesPrecedence() throws Exception {
        assertEquals("Display name", new TestableDrupalSync().title(
                cmd("clarin.eu:cr1:p_collection", "Display name", "Component title")));
    }
}
