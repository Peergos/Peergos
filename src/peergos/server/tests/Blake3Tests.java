package peergos.server.tests;

import org.junit.Assert;
import org.junit.Test;
import peergos.server.crypto.hash.Blake3;
import peergos.shared.util.ArrayOps;

import java.util.Random;

public class Blake3Tests {

    @Test
    public void correct() {
        Blake3 b3 = Blake3.initHash();
        byte[] data = new byte[4096];
        new Random(42).nextBytes(data);
        b3.update(data);
        byte[] hash = b3.doFinalize(32);
        Assert.assertEquals(ArrayOps.bytesToHex(hash), "3393625f68437730188ea2f582ac38f9ec6ead68ea6351caf36030d4a7b94ac5");
    }
}
