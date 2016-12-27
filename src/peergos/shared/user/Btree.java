package peergos.shared.user;

import peergos.shared.crypto.*;
import peergos.shared.ipfs.api.Multihash;
import peergos.shared.merklebtree.MaybeMultihash;
import peergos.shared.merklebtree.PairMultihash;
import peergos.shared.util.*;

import java.io.*;
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
    CompletableFuture<Boolean> put(User sharingKey, byte[] mapKey, Multihash value);

    /**
     *
     * @param sharingKey
     * @param mapKey
     * @return  the value stored under mapKey for sharingKey
     * @throws IOException
     */
    CompletableFuture<MaybeMultihash> get(UserPublicKey sharingKey, byte[] mapKey);

    /**
     *
     * @param sharingKey
     * @param mapKey
     * @return  hash(sharingKey.metadata) | the new root hash of the btree
     * @throws IOException
     */
    CompletableFuture<Boolean> remove(User sharingKey, byte[] mapKey);

}
