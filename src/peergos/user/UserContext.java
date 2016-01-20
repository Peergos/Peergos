package peergos.user;

import peergos.corenode.CoreNode;
import peergos.crypto.User;
import peergos.crypto.symmetric.SymmetricKey;
import peergos.server.storage.ContentAddressedStorage;

public class UserContext {
    public final String username;

    private final User user;
    private final SymmetricKey root;
    private final ContentAddressedStorage dht;
    private final CoreNode coreNode;

    public UserContext(String username, User user, SymmetricKey root, ContentAddressedStorage dht, CoreNode coreNode) {
        this.username = username;
        this.user = user;
        this.root = root;
        this.dht = dht;
        this.coreNode = coreNode;
    }
}
