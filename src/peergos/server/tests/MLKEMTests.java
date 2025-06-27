package peergos.server.tests;

import org.junit.Assert;
import org.junit.Test;
import peergos.server.Main;
import peergos.server.crypto.asymmetric.mlkem.fips203.FIPS203;
import peergos.server.crypto.asymmetric.mlkem.fips203.MimicloneFIPS203;
import peergos.server.crypto.asymmetric.mlkem.fips203.ParameterSet;
import peergos.server.crypto.asymmetric.mlkem.fips203.encaps.Encapsulation;
import peergos.server.crypto.asymmetric.mlkem.fips203.key.KeyPair;
import peergos.server.crypto.asymmetric.mlkem.fips203.key.SharedSecretKey;
import peergos.server.crypto.asymmetric.mlkem.fips203.message.CipherText;
import peergos.server.crypto.asymmetric.mlkem.fips203.message.MLKEMCipherText;
import peergos.shared.Crypto;
import peergos.shared.crypto.BoxingKeyPair;
import peergos.shared.crypto.InvalidCipherTextException;

import java.nio.charset.StandardCharsets;

public class MLKEMTests {
    private static Crypto crypto = Main.initCrypto();

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

    @Test
    public void hybrid() {
        BoxingKeyPair alice = BoxingKeyPair.randomHybrid(crypto).join();
        BoxingKeyPair bob = BoxingKeyPair.randomHybrid(crypto).join();

        byte[] msg = "G'day mate! This is hopefully post quantum secure!".getBytes(StandardCharsets.UTF_8);

        byte[] toSend = bob.publicBoxingKey.encryptMessageFor(msg, alice.secretBoxingKey).join();

        byte[] decrypted = bob.secretBoxingKey.decryptMessage(toSend, alice.publicBoxingKey).join();
        Assert.assertArrayEquals(decrypted, msg);
    }

    @Test
    public void hybridMsgTamper() {
        BoxingKeyPair alice = BoxingKeyPair.randomHybrid(crypto).join();
        BoxingKeyPair bob = BoxingKeyPair.randomHybrid(crypto).join();

        byte[] msg = "G'day mate! This is hopefully post quantum secure!".getBytes(StandardCharsets.UTF_8);

        byte[] toSend = bob.publicBoxingKey.encryptMessageFor(msg, alice.secretBoxingKey).join();
        for (int i=20; i < toSend.length; i++) {
            toSend[i] ^= 1;

            try {
                byte[] decrypted = bob.secretBoxingKey.decryptMessage(toSend, alice.publicBoxingKey).join();
            } catch (RuntimeException e) {}
            toSend[i] ^= 1;
        }
    }
}
