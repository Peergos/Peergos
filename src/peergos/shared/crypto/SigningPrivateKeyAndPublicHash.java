package peergos.shared.crypto;

import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;

public class SigningPrivateKeyAndPublicHash {
    public final PublicKeyHash publicKeyHash;
    public final SecretSigningKey secret;

    public SigningPrivateKeyAndPublicHash(PublicKeyHash publicKeyHash, SecretSigningKey secret) {
        this.publicKeyHash = publicKeyHash;
        this.secret = secret;
    }
}
