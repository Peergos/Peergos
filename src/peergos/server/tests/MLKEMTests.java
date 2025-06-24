package peergos.server.tests;

import org.junit.Assert;
import org.junit.Test;
import peergos.server.crypto.asymmetric.mlkem.fips203.FIPS203;
import peergos.server.crypto.asymmetric.mlkem.fips203.MimicloneFIPS203;
import peergos.server.crypto.asymmetric.mlkem.fips203.ParameterSet;
import peergos.server.crypto.asymmetric.mlkem.fips203.encaps.Encapsulation;
import peergos.server.crypto.asymmetric.mlkem.fips203.key.KeyPair;
import peergos.server.crypto.asymmetric.mlkem.fips203.key.SharedSecretKey;
import peergos.server.crypto.asymmetric.mlkem.fips203.message.CipherText;
import peergos.server.crypto.asymmetric.mlkem.fips203.message.MLKEMCipherText;

public class MLKEMTests {

    @Test
    public void usage() {
        FIPS203 fips203 = MimicloneFIPS203.create(ParameterSet.ML_KEM_1024);
        KeyPair bobKeys = fips203.generateKeyPair();

        // alice's keys are not involved
        Encapsulation alice = fips203.encapsulate(bobKeys.getEncapsulationKey());
        byte[] aliceSharedSecret = alice.getSharedSecretKey().getBytes();
        CipherText sentCipherText = alice.getCipherText();

        // bob
        CipherText cipherText = MLKEMCipherText.create(sentCipherText.getBytes());
        byte[] bobSharedSecret = fips203.decapsulate(bobKeys.getDecapsulationKey(), cipherText).getBytes();

        Assert.assertArrayEquals(aliceSharedSecret, bobSharedSecret);
    }
}
