package peergos.user;

import peergos.crypto.User;
import peergos.crypto.symmetric.SymmetricKey;

public class UserWithRoot {
    private final User user;
    private final SymmetricKey root;

    public UserWithRoot(User user, SymmetricKey root) {
        this.user = user;
        this.root = root;
    }

    public User getUser() {
        return user;
    }

    public SymmetricKey getRoot() {
        return root;
    }
}
