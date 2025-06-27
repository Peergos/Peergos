package peergos.server.crypto.asymmetric.mlkem.fips203.message;

public class MLKEMCipherText implements CipherText {

    private final byte[] cipherText;

    public MLKEMCipherText(byte[] cipherText) {
        this.cipherText = cipherText;
    }

    public static MLKEMCipherText create(byte[] cipherText) {
        return new MLKEMCipherText(cipherText.clone());
    }

    @Override
    public byte[] getBytes() {
        return cipherText.clone();
    }

}
