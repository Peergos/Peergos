package peergos.server.crypto.asymmetric.mlkem.fips203.encaps.mlkem;

import peergos.server.crypto.asymmetric.mlkem.fips203.encaps.Encapsulation;
import peergos.server.crypto.asymmetric.mlkem.fips203.key.SharedSecretKey;
import peergos.server.crypto.asymmetric.mlkem.fips203.key.mlkem.MLKEMSharedSecretKey;
import peergos.server.crypto.asymmetric.mlkem.fips203.message.CipherText;
import peergos.server.crypto.asymmetric.mlkem.fips203.message.MLKEMCipherText;

public class MLKEMEncapsulation implements Encapsulation {

    private final SharedSecretKey sharedSecretKey;
    private final CipherText cipherText;

    public MLKEMEncapsulation(SharedSecretKey sharedSecretKey, CipherText cipherText) {
        this.sharedSecretKey = sharedSecretKey;
        this.cipherText = cipherText;
    }

    static MLKEMEncapsulation build(byte[] sharedSecretKeyBytes, byte[] cipherTextBytes) {
        SharedSecretKey secretKey = MLKEMSharedSecretKey.create(sharedSecretKeyBytes);
        CipherText cipherText = MLKEMCipherText.create(cipherTextBytes);
        return new MLKEMEncapsulation(secretKey, cipherText);
    }

    @Override
    public SharedSecretKey getSharedSecretKey() {
        return sharedSecretKey;
    }

    @Override
    public CipherText getCipherText() {
        return cipherText;
    }
}
