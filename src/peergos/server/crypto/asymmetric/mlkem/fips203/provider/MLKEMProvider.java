package peergos.server.crypto.asymmetric.mlkem.fips203.provider;

import peergos.server.crypto.asymmetric.mlkem.fips203.ParameterSet;
import peergos.server.crypto.asymmetric.mlkem.fips203.decaps.provider.MLKEMDecapsulatorProvider;

import javax.crypto.KEMSpi;
import java.security.*;
import java.security.spec.AlgorithmParameterSpec;

public class MLKEMProvider implements KEMSpi {

    private final ParameterSet params;

    public MLKEMProvider(ParameterSet params) {
        this.params = params;
    }

    public static MLKEMProvider getMLKEM512Provider() {
        return new MLKEMProvider(ParameterSet.ML_KEM_512);
    }

    public static MLKEMProvider getMLKEM768Provider() {
        return new MLKEMProvider(ParameterSet.ML_KEM_768);
    }

    public static MLKEMProvider getMLKEM1024Provider() {
        return new MLKEMProvider(ParameterSet.ML_KEM_1024);
    }

    @Override
    public EncapsulatorSpi engineNewEncapsulator(PublicKey publicKey, AlgorithmParameterSpec spec, SecureRandom secureRandom) throws InvalidAlgorithmParameterException, InvalidKeyException {
        return null;
    }

    @Override
    public DecapsulatorSpi engineNewDecapsulator(PrivateKey privateKey, AlgorithmParameterSpec spec) throws InvalidAlgorithmParameterException, InvalidKeyException {
        return MLKEMDecapsulatorProvider.getInstance(privateKey, spec);
    }
}
