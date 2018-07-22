package peergos.server.tests;

import org.junit.*;
import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.password.*;

import java.util.*;

public class PasswordProtection {

    private static Crypto crypto = Crypto.initJava();

    @Test
    public void invertible() {
        byte[] secret = "Some secret data".getBytes();

        String password = "notagoodpassword";
        Cborable cbor = PasswordProtected.encryptWithPassword(secret, password, crypto.hasher, crypto.symmetricProvider, crypto.random);

        byte[] retrieved = PasswordProtected.decryptFromCbor(cbor, password, crypto.hasher, crypto.symmetricProvider, crypto.random);
        Assert.assertTrue("invertible", Arrays.equals(retrieved, secret));
    }
}
