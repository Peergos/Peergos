package peergos.util;

import org.ipfs.api.Multihash;
import peergos.crypto.UserPublicKey;
import peergos.server.merklebtree.MaybeMultihash;
import peergos.server.merklebtree.PairMultihash;

import java.io.IOException;

public interface Btree {
    /**
     *
     * @param sharingKey
     * @param mapKey
     * @param value
     * @return the new root hash of the btree
     * @throws IOException
     */
    PairMultihash put(UserPublicKey sharingKey,
                      byte[] mapKey,
                      Multihash value) throws IOException;

    /**
     *
     * @param sharingKey
     * @param mapKey
     * @return  the value stored under mapKey for sharingKey
     * @throws IOException
     */
    MaybeMultihash get(UserPublicKey sharingKey,
                                 byte[] mapKey) throws IOException;

    /**
     *
     * @param sharingKey
     * @param mapKey
     * @return  hash(sharingKey.metadata) | the new root hash of the btree
     * @throws IOException
     */
    PairMultihash remove(UserPublicKey sharingKey,
                            byte[] mapKey) throws IOException;
}
