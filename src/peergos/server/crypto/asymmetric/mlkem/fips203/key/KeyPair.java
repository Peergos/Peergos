package peergos.server.crypto.asymmetric.mlkem.fips203.key;

public interface KeyPair {

    EncapsulationKey getEncapsulationKey();

    DecapsulationKey getDecapsulationKey();

}
