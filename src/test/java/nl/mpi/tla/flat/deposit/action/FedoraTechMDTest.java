package nl.mpi.tla.flat.deposit.action;

import java.io.File;
import nl.mpi.tla.flat.deposit.sip.Resource;
import nl.mpi.tla.flat.deposit.sip.SIPInterface;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class FedoraTechMDTest {

    /** Minimal concrete Resource carrying just a file, for the non-CMD naming branch. */
    private static class FileResource extends Resource {
        FileResource(File file) {
            this.file = file;
        }
        @Override
        public void save(SIPInterface sip) {
        }
    }

    @Test
    public void fitsFileNameMirrorsFitsActionForPlainResource() throws Exception {
        FedoraTechMD action = new FedoraTechMD();
        Resource res = new FileResource(new File("/data/sub dir/rec.wav"));
        File fits = action.fitsFile(new File("/fits"), res, ".FITS.xml");
        assertEquals("_data_sub_dir_rec_wav.FITS.xml", fits.getName());
        assertEquals(new File("/fits"), fits.getParentFile());
    }
}
