package peergos.server.crypto.asymmetric.mlkem.fips203.encaps;

import peergos.server.crypto.asymmetric.mlkem.fips203.key.EncapsulationKey;
import peergos.server.crypto.asymmetric.mlkem.fips203.key.SharedSecretKey;

public interface Encapsulator {

    Encapsulation encapsulate(EncapsulationKey ek, byte[] entropy) throws EncapsulationException;

}
