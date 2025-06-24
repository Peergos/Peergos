package peergos.server.crypto.asymmetric.mlkem.fips203.encaps.provider;

import javax.crypto.KEM;
import javax.crypto.KEMSpi;

public class MLKEMEncapsulationProvider implements KEMSpi.EncapsulatorSpi {

    @Override
    public KEM.Encapsulated engineEncapsulate(int from, int to, String algorithm) {
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
