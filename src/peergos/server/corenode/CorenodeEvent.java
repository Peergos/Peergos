package peergos.server.corenode;

import peergos.shared.crypto.hash.*;

public class CorenodeEvent {

    public final String username;
    public final PublicKeyHash keyHash;

    public CorenodeEvent(String username, PublicKeyHash keyHash) {
        this.username = username;
        this.keyHash = keyHash;
    }
}
