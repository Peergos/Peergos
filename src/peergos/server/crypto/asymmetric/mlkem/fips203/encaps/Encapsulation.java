package peergos.server.crypto.asymmetric.mlkem.fips203.encaps;

import peergos.server.crypto.asymmetric.mlkem.fips203.key.SharedSecretKey;
import peergos.server.crypto.asymmetric.mlkem.fips203.message.CipherText;

public interface Encapsulation {

    SharedSecretKey getSharedSecretKey();

    CipherText getCipherText();

}
