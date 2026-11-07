package peergos.server.tests;

import org.junit.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.util.*;

public class HkdfTests {

    @Test
    public void rfc5869Case1() {
        byte[] ikm = ArrayOps.hexToBytes("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b");
        byte[] salt = ArrayOps.hexToBytes("000102030405060708090a0b0c");
        byte[] info = ArrayOps.hexToBytes("f0f1f2f3f4f5f6f7f8f9");
        byte[] okm = Hkdf.sha256(ikm, salt, info, 42);
        Assert.assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
                ArrayOps.bytesToHex(okm));
    }

    @Test
    public void rfc5869Case3() {
        byte[] ikm = ArrayOps.hexToBytes("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b");
        byte[] okm = Hkdf.sha256(ikm, new byte[0], new byte[0], 42);
        Assert.assertEquals("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8",
                ArrayOps.bytesToHex(okm));
    }
}
