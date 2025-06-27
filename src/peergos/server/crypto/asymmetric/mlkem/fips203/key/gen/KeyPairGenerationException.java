package peergos.server.crypto.asymmetric.mlkem.fips203.key.gen;

import peergos.server.crypto.asymmetric.mlkem.fips203.key.KeyPairException;

public class KeyPairGenerationException extends KeyPairException {
    public KeyPairGenerationException(String message) {
        super(message);
    }
}
