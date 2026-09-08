package peergos.shared.storage;

import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;

import java.util.*;

/** What the pre-bulk endpoints need to apply a commit and the bulk call does not.
 *
 *  A block sent to those endpoints carries its own signature, and a writer that is being created by
 *  this commit only becomes able to write once its parent's pointer update has registered it - so
 *  the old path also needs to know which writers those are, to order the calls around them.
 */
public class LegacyCommitInfo {

    public final Map<PublicKeyHash, SigningPrivateKeyAndPublicHash> signers;
    public final Set<PublicKeyHash> newWriters;

    public LegacyCommitInfo(Map<PublicKeyHash, SigningPrivateKeyAndPublicHash> signers,
                            Set<PublicKeyHash> newWriters) {
        this.signers = signers;
        this.newWriters = newWriters;
    }
}
