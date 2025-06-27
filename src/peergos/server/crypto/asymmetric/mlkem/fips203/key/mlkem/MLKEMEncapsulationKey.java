package peergos.server.crypto.asymmetric.mlkem.fips203.key.mlkem;

import peergos.server.crypto.asymmetric.mlkem.fips203.key.EncapsulationKey;

import java.util.Arrays;

public class MLKEMEncapsulationKey implements EncapsulationKey {

    private final byte[] keyBytes;

    private MLKEMEncapsulationKey(byte[] keyBytes) {
        this.keyBytes = keyBytes;
    }

    public static MLKEMEncapsulationKey create(byte[] keyBytes) {
        return new MLKEMEncapsulationKey(keyBytes.clone());
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
