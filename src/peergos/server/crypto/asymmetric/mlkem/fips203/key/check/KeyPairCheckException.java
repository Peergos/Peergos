package peergos.server.crypto.asymmetric.mlkem.fips203.key.check;

import peergos.server.crypto.asymmetric.mlkem.fips203.key.KeyPairException;

public class KeyPairCheckException extends KeyPairException {
    public KeyPairCheckException(String message) {
        super(message);
    }
}
