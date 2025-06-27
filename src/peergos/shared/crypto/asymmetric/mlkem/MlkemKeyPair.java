package peergos.shared.crypto.asymmetric.mlkem;

public class MlkemKeyPair {
    public final MlkemPublicKey publicKey;
    public final MlkemSecretKey secretKey;

    public MlkemKeyPair(MlkemPublicKey publicKey, MlkemSecretKey secretKey) {
        this.publicKey = publicKey;
        this.secretKey = secretKey;
    }
}
