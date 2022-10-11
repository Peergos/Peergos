package peergos.server;

import peergos.server.crypto.asymmetric.curve25519.*;
import peergos.server.crypto.hash.*;
import peergos.server.crypto.random.*;
import peergos.server.crypto.symmetric.*;
import peergos.shared.*;
import peergos.shared.crypto.asymmetric.curve25519.*;

public class JavaCrypto {

    public static Crypto init() {
        SafeRandomJava random = new SafeRandomJava();
        Salsa20Poly1305Java symmetricProvider = new Salsa20Poly1305Java();
        Ed25519Java signer = new Ed25519Java();
        Curve25519 boxer = new Curve25519Java();
        return Crypto.init(() -> new Crypto(random, new ScryptJava(), symmetricProvider, signer, boxer));
    }
}
