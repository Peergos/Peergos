package peergos.shared.user;

import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.MaybeMultihash;
import peergos.shared.storage.*;

import java.io.*;
import java.util.concurrent.*;

public interface MutableTree {

    /**
     *
     * @param owner
     * @param sharingKey
     * @param mapKey
     * @param value
     * @param tid
     * @return the new root hash of the tree
     * @throws IOException
     */
    CompletableFuture<Boolean> put(PublicKeyHash owner,
                                   SigningPrivateKeyAndPublicHash sharingKey,
                                   byte[] mapKey,
                                   MaybeMultihash existing,
                                   Multihash value,
                                   TransactionId tid);

    /**
     *
     * @param owner
     * @param sharingKey
     * @param mapKey
     * @return  the value stored under mapKey for sharingKey
     * @throws IOException
     */
    CompletableFuture<MaybeMultihash> get(PublicKeyHash owner, PublicKeyHash sharingKey, byte[] mapKey);

    /**
     *
     * @param owner
     * @param sharingKey
     * @param mapKey
     * @return  hash(sharingKey.metadata) | the new root hash of the tree
     * @throws IOException
     */
    CompletableFuture<Boolean> remove(PublicKeyHash owner,
                                      SigningPrivateKeyAndPublicHash sharingKey,
                                      byte[] mapKey,
                                      MaybeMultihash existing,
                                      TransactionId tid);


    class CasException extends RuntimeException {
        public CasException(MaybeMultihash actualExisting, MaybeMultihash claimedExisting) {
            super("CAS exception updating cryptree node. existing: " + actualExisting + ", claimed: " + claimedExisting);
        }
    }
}
