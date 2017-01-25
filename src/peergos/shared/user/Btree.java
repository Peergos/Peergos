package peergos.shared.user;

import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.merklebtree.MaybeMultihash;

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
    CompletableFuture<Boolean> put(SigningKeyPair sharingKey, byte[] mapKey, Multihash value);

    /**
     *
     * @param sharingKey
     * @param mapKey
     * @return  the value stored under mapKey for sharingKey
     * @throws IOException
     */
    CompletableFuture<MaybeMultihash> get(PublicSigningKey sharingKey, byte[] mapKey);

    /**
     *
     * @param sharingKey
     * @param mapKey
     * @return  hash(sharingKey.metadata) | the new root hash of the btree
     * @throws IOException
     */
    CompletableFuture<Boolean> remove(SigningKeyPair sharingKey, byte[] mapKey);

}
