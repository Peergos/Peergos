package peergos.server.crypto.asymmetric.mlkem.fips203.decaps;

import peergos.server.crypto.asymmetric.mlkem.fips203.key.DecapsulationKey;
import peergos.server.crypto.asymmetric.mlkem.fips203.key.SharedSecretKey;
import peergos.server.crypto.asymmetric.mlkem.fips203.message.CipherText;

public interface Decapsulator {

    SharedSecretKey decapsulate(DecapsulationKey key, CipherText cipherText) throws DecapsulationException;

}
