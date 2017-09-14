package peergos.shared.user;

import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.merklebtree.MaybeMultihash;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public interface Btree {

    /**
     *
     * @param sharingKey
     * @param mapKey
     * @param value
     * @return the new root hash of the btree
     * @throws IOException
     */
    CompletableFuture<Boolean> put(SigningPrivateKeyAndPublicHash sharingKey, byte[] mapKey, MaybeMultihash existing, Multihash value);

    /**
     *
     * @param sharingKey
     * @param mapKey
     * @return  the value stored under mapKey for sharingKey
     * @throws IOException
     */
    CompletableFuture<MaybeMultihash> get(PublicKeyHash sharingKey, byte[] mapKey);

    /**
     *
     * @param sharingKey
     * @param mapKey
     * @return  hash(sharingKey.metadata) | the new root hash of the btree
     * @throws IOException
     */
    CompletableFuture<Boolean> remove(SigningPrivateKeyAndPublicHash sharingKey, byte[] mapKey);


    class CasException extends RuntimeException {

        public CasException(MaybeMultihash actualExisting, MaybeMultihash claimedExisting) {
            super("CAS exception updating cryptree node. existing: " + actualExisting + ", claimed: " + claimedExisting);
        }
    }
}
