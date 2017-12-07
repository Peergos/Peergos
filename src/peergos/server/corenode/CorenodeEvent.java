package peergos.server.corenode;

import peergos.shared.crypto.hash.*;

/** This propagates a user changing their root public key (by signing up, or changing their password)
 *
 */
public class CorenodeEvent {

    public final String username;
    public final PublicKeyHash keyHash;

    public CorenodeEvent(String username, PublicKeyHash keyHash) {
        this.username = username;
        this.keyHash = keyHash;
    }
}
