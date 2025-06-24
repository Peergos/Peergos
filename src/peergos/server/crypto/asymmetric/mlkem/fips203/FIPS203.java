package peergos.server.crypto.asymmetric.mlkem.fips203;

import peergos.server.crypto.asymmetric.mlkem.fips203.encaps.Encapsulation;
import peergos.server.crypto.asymmetric.mlkem.fips203.key.*;
import peergos.server.crypto.asymmetric.mlkem.fips203.key.check.KeyPairCheckException;
import peergos.server.crypto.asymmetric.mlkem.fips203.key.gen.KeyPairGenerationException;
import peergos.server.crypto.asymmetric.mlkem.fips203.message.CipherText;
import peergos.server.crypto.asymmetric.mlkem.fips203.key.SharedSecretKey;

public interface FIPS203 {

    /**
     * Convenience method to verify the ParameterSet of the underlyig implementation.
     */
    ParameterSet getParameterSet();

    /**
     * Implementation of the KeyGen algorithm as specified in the FIPS203 Specification
     */
    KeyPair generateKeyPair() throws KeyPairGenerationException;

    void keyPairCheck(KeyPair keyPair) throws KeyPairCheckException;

    /**
     * Implementation of the Encaps algorithm as specified in the FIPS203 Specification
     * @return An array of exactly 32 bytes representing the encapsulated cyphertext
     */
    Encapsulation encapsulate(EncapsulationKey key);

    /**
     * Implementation of the Decaps algorithm as specified in the FIPS203 Specification
     * @return An array of exactly 32 bytes representing the decapsulated cleartext.
     */
    SharedSecretKey decapsulate(DecapsulationKey key, CipherText cipherText);

}
