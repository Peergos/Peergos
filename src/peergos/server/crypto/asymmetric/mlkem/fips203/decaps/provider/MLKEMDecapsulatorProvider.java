package peergos.server.crypto.asymmetric.mlkem.fips203.decaps.provider;

import peergos.server.crypto.asymmetric.mlkem.fips203.ParameterSet;
import peergos.server.crypto.asymmetric.mlkem.fips203.decaps.Decapsulator;
import peergos.server.crypto.asymmetric.mlkem.fips203.decaps.mlkem.MLKEMDecapsulator;

import javax.crypto.DecapsulateException;
import javax.crypto.KEMSpi;
import javax.crypto.SecretKey;
import java.security.PrivateKey;
import java.security.spec.AlgorithmParameterSpec;

public class MLKEMDecapsulatorProvider implements KEMSpi.DecapsulatorSpi {

    private final Decapsulator decapsulator;

    public MLKEMDecapsulatorProvider(Decapsulator decapsulator) {
        this.decapsulator = decapsulator;
    }

    public static MLKEMDecapsulatorProvider getInstance(PrivateKey privateKey, AlgorithmParameterSpec spec) {
        return new MLKEMDecapsulatorProvider(MLKEMDecapsulator.create(ParameterSet.ML_KEM_768));
    }

    @Override
    public SecretKey engineDecapsulate(byte[] encapsulation, int from, int to, String algorithm) throws DecapsulateException {

        return null;

    }

    @Override
    public int engineSecretSize() {
        return 0;
    }

    @Override
    public int engineEncapsulationSize() {
        return 0;
    }
}
