package peergos.server.crypto.asymmetric.mlkem.fips203.key.mlkem;

import peergos.server.crypto.asymmetric.mlkem.fips203.key.DecapsulationKey;

import java.util.Arrays;

public class MLKEMDecapsulationKey implements DecapsulationKey {

    private final byte[] keyBytes;

    private MLKEMDecapsulationKey(byte[] keyBytes) {
        this.keyBytes = keyBytes;
    }

    public static MLKEMDecapsulationKey create(byte[] keyBytes) {
        return new MLKEMDecapsulationKey(keyBytes.clone());
    }

    @Override
    public byte[] getBytes() {
        return keyBytes.clone();
    }

    @Override
    public void destroy() {
        Arrays.fill(keyBytes, (byte)0);
    }
}
