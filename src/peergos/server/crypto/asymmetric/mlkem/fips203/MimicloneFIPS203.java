package peergos.server.crypto.asymmetric.mlkem.fips203;

import peergos.server.crypto.asymmetric.mlkem.MlkemSecureRandom;
import peergos.server.crypto.asymmetric.mlkem.fips203.decaps.Decapsulator;
import peergos.server.crypto.asymmetric.mlkem.fips203.decaps.mlkem.MLKEMDecapsulator;
import peergos.server.crypto.asymmetric.mlkem.fips203.encaps.Encapsulation;
import peergos.server.crypto.asymmetric.mlkem.fips203.encaps.Encapsulator;
import peergos.server.crypto.asymmetric.mlkem.fips203.encaps.mlkem.MLKEMEncapsulator;
import peergos.server.crypto.asymmetric.mlkem.fips203.key.DecapsulationKey;
import peergos.server.crypto.asymmetric.mlkem.fips203.key.EncapsulationKey;
import peergos.server.crypto.asymmetric.mlkem.fips203.key.KeyPair;
import peergos.server.crypto.asymmetric.mlkem.fips203.key.SharedSecretKey;
import peergos.server.crypto.asymmetric.mlkem.fips203.key.check.KeyPairCheckException;
import peergos.server.crypto.asymmetric.mlkem.fips203.key.gen.KeyPairGeneration;
import peergos.server.crypto.asymmetric.mlkem.fips203.key.gen.KeyPairGenerationException;
import peergos.server.crypto.asymmetric.mlkem.fips203.key.gen.mlkem.MLKEMKeyPairGenerator;
import peergos.server.crypto.asymmetric.mlkem.fips203.message.CipherText;

import java.security.SecureRandom;

import static peergos.server.crypto.asymmetric.mlkem.CryptoUtils.zero;

public class MimicloneFIPS203 implements FIPS203 {

    public static FIPS203 create(ParameterSet params) {
        return new MimicloneFIPS203(params);
    }



    // FIPS 203 Parameter Set assigned
    private final ParameterSet parameterSet;
    private final SecureRandom secureRandom;
    private final KeyPairGeneration keyPairGenerator;
    private final Encapsulator encapsulator;
    private final Decapsulator decapsulator;

    private MimicloneFIPS203(ParameterSet parameterSet) {

        // Assign the chosen parameter set
        this.parameterSet = parameterSet;

        secureRandom = MlkemSecureRandom.getSecureRandom(parameterSet.getMinSecurityStrength());

        // Initialize the Key Pair Generator
        this.keyPairGenerator = MLKEMKeyPairGenerator.create(parameterSet);

        // Initialize the Encapsulator
        this.encapsulator = MLKEMEncapsulator.create(parameterSet);

        // Initialize the Decapsulator
        this.decapsulator = MLKEMDecapsulator.create(parameterSet);

    }

    @Override
    public ParameterSet getParameterSet() {
        return parameterSet;
    }

    /**
     * Implements Algorithm 19 (ML-KEM.KeyGen) of the FIPS203 Specification
     *
     * @return A FIPS203KeyPair instance.
     */
    @Override
    public KeyPair generateKeyPair() throws KeyPairGenerationException {

        // FIPS203:Algorithm19:Line1
        // Generate 'd', a value of 32 random bytes
        byte[] d = new byte[32];
        secureRandom.nextBytes(d);

        // FIPS203:Algorithm19:Line2
        // Generate 'z', a value of 32 random bytes
        byte[] z = new byte[32];
        secureRandom.nextBytes(z);

        // FIPS203:Algorithm19:Line3
        // The spec requires a null check here for d and z, but it isn't possible
        // for them to be null.  Checking would raise a compiler error

        // Invoke Key Generation
        KeyPair keyPair = keyPairGenerator.generateKeyPair(d, z);

        // ZERO: d
        zero(d);

        // ZERO: z
        zero(z);

        // Return wrapped KeyPair
        return keyPair;

    }

    /**
     * Implements Algorithm
     * @param keyPair
     * @throws KeyPairCheckException
     */
    @Override
    public void keyPairCheck(KeyPair keyPair) throws KeyPairCheckException {
        // TODO: Implement key pair checking
        throw new KeyPairCheckException("Key pair checking has not yet been implemented.");
    }

    /**
     * Implements Algorithm 20 (ML-KEM.Encaps) of the FIPS203 Specification.
     *
     * This generates 32-bytes of entropy and passes it along to the internal implementation.
     *
     * @param key An {@code EncapsulationKey} instance.
     * @return A {@code SharedSecretKey}
     */
    @Override
    public Encapsulation encapsulate(EncapsulationKey key) {

        // Generate 32 bytes of securely random entropy
        byte[] m = new byte[32];
        secureRandom.nextBytes(m);

        // The spec requires a null check here for m, but Java is designed such that this isn't possible.

        // Perform the encapsulation
        Encapsulation encapsulation = encapsulator.encapsulate(key, m); // LAST USE: m

        // ZERO: m
        zero(m);

        // Return wrapped result value
        return encapsulation;

    }

    /**
     * Implements Algorithm 21 (ML-KEM.Decaps) of the FIPS203 Specification.
     * No randomness is generated so this is a simple pass-through to the internal implementation.
     *
     * @param key
     * @param cipherText
     * @return
     */
    @Override
    public SharedSecretKey decapsulate(DecapsulationKey key, CipherText cipherText) {
        return decapsulator.decapsulate(key, cipherText);
    }
}
