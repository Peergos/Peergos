package peergos.server.crypto.asymmetric.mlkem.fips203.key.gen;

import peergos.server.crypto.asymmetric.mlkem.fips203.key.KeyPair;

public interface KeyPairGeneration {

    KeyPair generateKeyPair(byte[] d, byte[] z);

}
