package peergos.shared.user;

import peergos.shared.crypto.*;
import peergos.shared.crypto.symmetric.SymmetricKey;

public class UserWithRoot {
    private final SigningKeyPair signer;
    private final BoxingKeyPair boxer;
    private final SymmetricKey root;

    public UserWithRoot(SigningKeyPair signer, BoxingKeyPair boxer, SymmetricKey root) {
        this.signer = signer;
        this.boxer = boxer;
        this.root = root;
    }

    public SigningKeyPair getUser() {
        return signer;
    }

    public BoxingKeyPair getBoxingPair() {
        return boxer;
    }
    public SymmetricKey getRoot() {
        return root;
    }
}
