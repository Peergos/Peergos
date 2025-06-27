package peergos.server.crypto.asymmetric.mlkem.fips203.key.mlkem;

import peergos.server.crypto.asymmetric.mlkem.fips203.key.SharedSecretKey;

import java.util.Arrays;

public class MLKEMSharedSecretKey implements SharedSecretKey {

    private final byte[] sharedSecret;

    public MLKEMSharedSecretKey(byte[] sharedSecret) {
        this.sharedSecret = sharedSecret;
    }

    public static MLKEMSharedSecretKey create(byte[] sharedSecret) {
        return new MLKEMSharedSecretKey(sharedSecret);
    }

    @Override
    public byte[] getBytes() {
        return sharedSecret.clone();
    }

    @Override
    public void destroy() {
        Arrays.fill(sharedSecret, (byte)0);
    }
}
