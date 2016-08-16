package peergos.shared.user;

import peergos.shared.crypto.User;
import peergos.shared.crypto.symmetric.SymmetricKey;

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
