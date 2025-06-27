package peergos.server.crypto.asymmetric.mlkem.fips203.key.gen.provider;

import peergos.server.crypto.asymmetric.mlkem.fips203.ParameterSet;

import java.security.KeyPair;
import java.security.KeyPairGeneratorSpi;
import java.security.SecureRandom;

public class MLKEMKeyGenerationProvider extends KeyPairGeneratorSpi {

    private final ParameterSet params;

    public MLKEMKeyGenerationProvider(ParameterSet params) {
        this.params = params;
    }

    public static MLKEMKeyGenerationProvider getMLKEM512Provider() {
        return new MLKEMKeyGenerationProvider(ParameterSet.ML_KEM_512);
    }

    public static MLKEMKeyGenerationProvider getMLKEM768Provider() {
        return new MLKEMKeyGenerationProvider(ParameterSet.ML_KEM_768);
    }

    public static MLKEMKeyGenerationProvider getMLKEM1024Provider() {
        return new MLKEMKeyGenerationProvider(ParameterSet.ML_KEM_1024);
    }

    @Override
    public void initialize(int keysize, SecureRandom random) {

    }

    @Override
    public KeyPair generateKeyPair() {
        return null;
    }
}
