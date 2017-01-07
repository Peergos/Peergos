package peergos.shared.user;

import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.symmetric.SymmetricKey;

public class UserWithRoot {
    private final User user;
    private final BoxingKeyPair boxer;
    private final SymmetricKey root;

    public UserWithRoot(User user, BoxingKeyPair boxer, SymmetricKey root) {
        this.user = user;
        this.boxer = boxer;
        this.root = root;
    }

    public User getUser() {
        return user;
    }

    public BoxingKeyPair getBoxingPair() {
        return boxer;
    }
    public SymmetricKey getRoot() {
        return root;
    }
}
