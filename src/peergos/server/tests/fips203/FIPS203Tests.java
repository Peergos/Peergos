package peergos.server.tests.fips203;

import org.junit.Test;
import peergos.server.crypto.asymmetric.mlkem.fips203.FIPS203;
import peergos.server.crypto.asymmetric.mlkem.fips203.MimicloneFIPS203;
import peergos.server.crypto.asymmetric.mlkem.fips203.ParameterSet;
import peergos.server.crypto.asymmetric.mlkem.fips203.encaps.Encapsulation;
import peergos.server.crypto.asymmetric.mlkem.fips203.key.DecapsulationKey;
import peergos.server.crypto.asymmetric.mlkem.fips203.key.EncapsulationKey;
import peergos.server.crypto.asymmetric.mlkem.fips203.key.KeyPair;
import peergos.server.crypto.asymmetric.mlkem.fips203.key.SharedSecretKey;
import peergos.server.crypto.asymmetric.mlkem.fips203.message.CipherText;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

import static org.junit.Assert.*;

public class FIPS203Tests {

    private static final String CIPHER_ALGORITHM = "AES/GCM/NoPadding";
    private static final String SECURE_RANDOM_ALGORITHM = "DRBG";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 16;

    /**
     * Validates that an invocation of {@link FIPS203#generateKeyPair()} with the provided instance creates a
     * {@link KeyPair} instance that meets all the invariants in terms of existence and size.  Note that because
     * the concrete implementation of {@link FIPS203} must generate random values as part of the process it is
     * not possible to provide static test vector validations at this level.
     *
     * @param fips203MlKem An instance of the {@link FIPS203} interface using one of the defined {@link ParameterSet}s.
     * @return A {@link KeyPair} object which has passed all checks relating to existing and appropriate length.
     */
    private KeyPair validateKeyPairGeneration(
            FIPS203 fips203MlKem
    ) {

        // Retrieve the parameter set
        ParameterSet parameterSet = fips203MlKem.getParameterSet();

        // Generate the key pair
        KeyPair keyPair = fips203MlKem.generateKeyPair();

        // Ensure the KeyPair object is not null
        assertNotNull(keyPair);

        // Get the EncapsulationKey
        EncapsulationKey encapsulationKey = keyPair.getEncapsulationKey();
        assertNotNull(encapsulationKey);

        // Validate the encapsulation key bytes
        byte[] encapsulationKeyBytes = encapsulationKey.getBytes();
        assertNotNull(encapsulationKeyBytes);
        assertEquals(parameterSet.getEncapsulationKeyLength(), encapsulationKeyBytes.length);

        // Validate the decapsulation key
        DecapsulationKey decapsulationKey = keyPair.getDecapsulationKey();
        assertNotNull(decapsulationKey);

        // Validate the decapsulation key bytes
        byte[] decapsulationKeyBytes = decapsulationKey.getBytes();
        assertNotNull(decapsulationKeyBytes);
        assertEquals(parameterSet.getDecapsulationKeyLength(), decapsulationKeyBytes.length);

        return keyPair;
    }

    private Encapsulation validateEncapsulation(
            FIPS203 fips203MlKem,
            EncapsulationKey encapsulationKey
    ) {

        // Retrieve the parameter set
        ParameterSet parameterSet = fips203MlKem.getParameterSet();

        // Encapsulate the shared secret
        Encapsulation encapsulation = fips203MlKem.encapsulate(encapsulationKey);

        // Validate that the encapsulation is not null
        assertNotNull(encapsulation);

        // Retrieve and validate the Shared Secret Key
        SharedSecretKey sharedSecretKey = encapsulation.getSharedSecretKey();
        assertNotNull(sharedSecretKey);

        // Retrieve and validate the raw shared secret bytes
        byte[] sharedSecretKeyBytes = sharedSecretKey.getBytes();
        assertNotNull(sharedSecretKeyBytes);
        assertEquals(parameterSet.getSharedSecretKeyLength(), sharedSecretKeyBytes.length);

        // Retrieve the cipher text
        CipherText cipherText = encapsulation.getCipherText();
        assertNotNull(cipherText);

        // Retrieve and validate the raw ciphertext bytes
        byte[] cipherTextBytes = cipherText.getBytes();
        assertNotNull(cipherTextBytes);
        assertEquals(parameterSet.getCiphertextLength(), cipherTextBytes.length);

        return encapsulation;

    }

    private SharedSecretKey validateDecapsulation(
            FIPS203 fips203MlKem,
            DecapsulationKey decapsulationKey,
            CipherText cipherText
    ) {

        // Retrieve the parameter set
        ParameterSet parameterSet = fips203MlKem.getParameterSet();

        // Decapsulate the shared secret
        SharedSecretKey sharedSecretKey = fips203MlKem.decapsulate(decapsulationKey, cipherText);
        assertNotNull(sharedSecretKey);

        // Validate the bytes
        byte[] sharedSecretKeyBytes = sharedSecretKey.getBytes();
        assertNotNull(sharedSecretKeyBytes);
        assertEquals(parameterSet.getSharedSecretKeyLength(), sharedSecretKeyBytes.length);

        return sharedSecretKey;

    }

    private void validateAES256CipherCompatability(byte[] sharedSecretKey) throws Exception {

        // Define a plaintext message
        String message = "Validation of Mimiclone FIPS203 Implementation";

        // Build a Java SecretKey from the bytes
        SecretKey secretKey = new SecretKeySpec(sharedSecretKey, "AES");

        // Create initialization vector
        byte[] iv = new byte[GCM_IV_LENGTH];
        SecureRandom.getInstance(SECURE_RANDOM_ALGORITHM).nextBytes(iv);

        // Get an instance of the AES cipher
        Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH * 8, iv);

        // Encrypt the value
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec);
        byte[] cipherText = cipher.doFinal(message.getBytes());
        byte[] encryptedData = new byte[iv.length + cipherText.length];
        System.arraycopy(iv, 0, encryptedData, 0, iv.length);
        System.arraycopy(cipherText, 0, encryptedData, iv.length, cipherText.length);

        // Print out the results for visual inspection
        String base64EncryptedMessage = Base64.getEncoder().encodeToString(encryptedData);
        System.out.println("Base64 Encrypted Message: " + base64EncryptedMessage);

        // Decrypt the value
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec);

        byte[] decodedData = Base64.getDecoder().decode(base64EncryptedMessage);
        byte[] ivDecode = new byte[GCM_IV_LENGTH];
        System.arraycopy(decodedData, 0, ivDecode, 0, ivDecode.length);

        Cipher decipher = Cipher.getInstance(CIPHER_ALGORITHM);
        GCMParameterSpec despec = new GCMParameterSpec(GCM_TAG_LENGTH * 8, ivDecode);
        decipher.init(Cipher.DECRYPT_MODE, secretKey, despec);
        byte[] decrypted = cipher.doFinal(decodedData, GCM_IV_LENGTH, decodedData.length - GCM_IV_LENGTH);

        String restoredMessage = new String(decrypted);
        assertEquals(message, restoredMessage);
        assertArrayEquals(iv, ivDecode);
        assertArrayEquals(encryptedData, decodedData);

    }

    @Test
    public void testMLKEM512Interface() {

        // Validate the Parameter Set
        ParameterSet parameterSet = ParameterSet.ML_KEM_512;
        FIPS203 fips203MlKem512 = MimicloneFIPS203.create(parameterSet);
        assertEquals(parameterSet, fips203MlKem512.getParameterSet());

        // Validate Key Pair Generation
        KeyPair keyPair = validateKeyPairGeneration(fips203MlKem512);

        // Validate Encapsulation
        Encapsulation encapsulation = validateEncapsulation(
                fips203MlKem512,
                keyPair.getEncapsulationKey()
        );

        // Validate Decapsulation
        SharedSecretKey sharedSecretKey = validateDecapsulation(
                fips203MlKem512,
                keyPair.getDecapsulationKey(),
                encapsulation.getCipherText()
        );

        // Validate shared secret was unchanged at the byte level
        assertArrayEquals(encapsulation.getSharedSecretKey().getBytes(), sharedSecretKey.getBytes());

        try {
            validateAES256CipherCompatability(sharedSecretKey.getBytes());
        } catch (Exception e) {
            fail(e.getMessage());
        }

    }

    @Test
    public void testMLKEM768Interface() {

        // Validate the Parameter Set
        ParameterSet parameterSet = ParameterSet.ML_KEM_768;
        FIPS203 fips203MlKem768 = MimicloneFIPS203.create(parameterSet);
        assertEquals(parameterSet, fips203MlKem768.getParameterSet());

        // Validate Key Pair Generation
        KeyPair keyPair = validateKeyPairGeneration(fips203MlKem768);

        // Validate Encapsulation
        Encapsulation encapsulation = validateEncapsulation(fips203MlKem768, keyPair.getEncapsulationKey());

        // Validate Decapsulation
        SharedSecretKey sharedSecretKey = validateDecapsulation(
                fips203MlKem768,
                keyPair.getDecapsulationKey(),
                encapsulation.getCipherText()
        );

        // Validate shared secret was unchanged at the byte level
        assertArrayEquals(encapsulation.getSharedSecretKey().getBytes(), sharedSecretKey.getBytes());

        try {
            validateAES256CipherCompatability(sharedSecretKey.getBytes());
        } catch (Exception e) {
            fail(e.getMessage());
        }

    }

    @Test
    public void testMLKEM1024Interface() {

        // Validate the Parameter Set
        ParameterSet parameterSet = ParameterSet.ML_KEM_1024;
        FIPS203 fips203MlKem1024 = MimicloneFIPS203.create(parameterSet);
        assertEquals(parameterSet, fips203MlKem1024.getParameterSet());

        // Validate Key Pair Generation
        KeyPair keyPair = validateKeyPairGeneration(fips203MlKem1024);

        // Base64 Encode
        Base64.Encoder encoder = Base64.getEncoder();
        String encodedEncapsKey = encoder.encodeToString(keyPair.getEncapsulationKey().getBytes());
        String encodedDecapsKey = encoder.encodeToString(keyPair.getDecapsulationKey().getBytes());
        System.out.println("Base64 Encoded EncapsKey Length: " + encodedEncapsKey.length());
        System.out.println("Base64 Encoded DecapsKey Length: " + encodedDecapsKey.length());

        // Validate Encapsulation
        Encapsulation encapsulation = validateEncapsulation(fips203MlKem1024, keyPair.getEncapsulationKey());

        // Validate Decapsulation
        SharedSecretKey sharedSecretKey = validateDecapsulation(
                fips203MlKem1024,
                keyPair.getDecapsulationKey(),
                encapsulation.getCipherText()
        );

        String encodedSharedSecretKey = encoder.encodeToString(sharedSecretKey.getBytes());
        System.out.println("Base64 Encoded SharedSecretKey Length: " + encodedSharedSecretKey.length());

        // Validate shared secret was unchanged at the byte level
        assertArrayEquals(encapsulation.getSharedSecretKey().getBytes(), sharedSecretKey.getBytes());

        try {
            validateAES256CipherCompatability(sharedSecretKey.getBytes());
        } catch (Exception e) {
            fail(e.getMessage());
        }

    }

}
