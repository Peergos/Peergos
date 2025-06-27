package peergos.server.crypto.asymmetric.mlkem.fips203.provider;

import peergos.server.crypto.asymmetric.mlkem.fips203.FIPS203;
import peergos.server.crypto.asymmetric.mlkem.fips203.ParameterSet;
import peergos.server.crypto.asymmetric.mlkem.fips203.key.gen.provider.MLKEMKeyGenerationProvider;

import java.security.*;
import java.security.spec.AlgorithmParameterSpec;

public class MimicloneSecurityProvider extends Provider implements javax.crypto.KEMSpi {

    public static final String PROVIDER_NAME = "Mimiclone";
    private static final String PROVIDER_VERSION = "1.0.0";
    private static final String PROVIDER_INFO = "Mimiclone Provider " +
            "(ML-KEM-512/ML-KEM-768/ML-KEM-1024: Key Pair Generation, Kem/Encaps, KEM/Decaps)";

    private static final class ProviderService extends Provider.Service {
        ProviderService(Provider p, String type, String algo, String cn) {
            super(p, type, algo, cn, null, null);
        }

        @Override
        public Object newInstance(Object ctrParamObj)
                throws NoSuchAlgorithmException {
            String type = getType();
            if (ctrParamObj != null) {
                throw new InvalidParameterException
                        ("constructorParameter not used with " + type + " engines");
            }

            String algo = getAlgorithm();
            switch (type) {
                case "KeyPairGenerator": {
                    switch (algo) {
                        case "ML-KEM-512": return MLKEMKeyGenerationProvider.getMLKEM512Provider();
                        case "ML-KEM-768": return MLKEMKeyGenerationProvider.getMLKEM768Provider();
                        case "ML-KEM-1024": return MLKEMKeyGenerationProvider.getMLKEM1024Provider();
                        default: throw new NoSuchAlgorithmException("Algorithm not supported: " + algo);
                    }
                }
                case "KEM": {
                    switch (algo) {
                        case "ML-KEM-512": return MLKEMProvider.getMLKEM512Provider();
                        case "ML-KEM-768": return MLKEMProvider.getMLKEM768Provider();
                        case "ML-KEM-1024": return MLKEMProvider.getMLKEM1024Provider();
                        default: throw new NoSuchAlgorithmException("Algorithm not supported: " + algo);
                    }
                }
            }
            throw new ProviderException("No impl for " + algo +
                    " " + type);
        }
    }

    public MimicloneSecurityProvider() {
        super(PROVIDER_NAME, PROVIDER_VERSION, PROVIDER_INFO);

        put(PROVIDER_NAME, this);
        putService(new MimicloneSecurityProvider.ProviderService(this,
                "KeyPairGenerator",
                "ML-KEM-512",
                MLKEMKeyGenerationProvider.class.getName()));
        putService(new MimicloneSecurityProvider.ProviderService(this,
                "KeyPairGenerator",
                "ML-KEM-768",
                MLKEMKeyGenerationProvider.class.getName()));
        putService(new MimicloneSecurityProvider.ProviderService(this,
                "KeyPairGenerator",
                "ML-KEM-1024",
                MLKEMKeyGenerationProvider.class.getName()));
        putService(new MimicloneSecurityProvider.ProviderService(this,
                "KEM",
                "ML-KEM-512",
                MLKEMKeyGenerationProvider.class.getName()));
        putService(new MimicloneSecurityProvider.ProviderService(this,
                "KEM",
                "ML-KEM-768",
                MLKEMKeyGenerationProvider.class.getName()));
        putService(new MimicloneSecurityProvider.ProviderService(this,
                "KEM",
                "ML-KEM-1024",
                MLKEMKeyGenerationProvider.class.getName()));
    }

    public void install() {
        Security.addProvider(this);
    }

    @Override
    public EncapsulatorSpi engineNewEncapsulator(PublicKey publicKey, AlgorithmParameterSpec spec, SecureRandom secureRandom) throws InvalidAlgorithmParameterException, InvalidKeyException {
        return null;
    }

    @Override
    public DecapsulatorSpi engineNewDecapsulator(PrivateKey privateKey, AlgorithmParameterSpec spec) throws InvalidAlgorithmParameterException, InvalidKeyException {
        return null;
    }
}
