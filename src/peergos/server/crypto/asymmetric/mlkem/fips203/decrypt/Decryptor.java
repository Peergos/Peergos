package peergos.server.crypto.asymmetric.mlkem.fips203.decrypt;

public interface Decryptor {

    byte[] decrypt(byte[] dkPKE, byte[] cipherText);

}
