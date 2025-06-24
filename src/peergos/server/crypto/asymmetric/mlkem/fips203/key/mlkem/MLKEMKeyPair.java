package peergos.server.crypto.asymmetric.mlkem.fips203.key.mlkem;

import peergos.server.crypto.asymmetric.mlkem.fips203.key.DecapsulationKey;
import peergos.server.crypto.asymmetric.mlkem.fips203.key.EncapsulationKey;
import peergos.server.crypto.asymmetric.mlkem.fips203.key.KeyPair;

import java.util.Objects;

public class MLKEMKeyPair implements KeyPair {
    private final EncapsulationKey getEncapsulationKey;
    private final DecapsulationKey getDecapsulationKey;

    public MLKEMKeyPair(EncapsulationKey getEncapsulationKey, DecapsulationKey getDecapsulationKey) {
        this.getEncapsulationKey = getEncapsulationKey;
        this.getDecapsulationKey = getDecapsulationKey;
    }

    public static KeyPair fromBytes(byte[] ek, byte[] dk) {
        return new MLKEMKeyPair(MLKEMEncapsulationKey.create(ek), MLKEMDecapsulationKey.create(dk));
    }

    @Override
    public EncapsulationKey getEncapsulationKey() {
        return getEncapsulationKey;
    }

    @Override
    public DecapsulationKey getDecapsulationKey() {
        return getDecapsulationKey;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MLKEMKeyPair that = (MLKEMKeyPair) o;
        return Objects.equals(getEncapsulationKey, that.getEncapsulationKey) && Objects.equals(getDecapsulationKey, that.getDecapsulationKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(getEncapsulationKey, getDecapsulationKey);
    }
}
