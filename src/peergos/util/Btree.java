package peergos.util;

import peergos.crypto.UserPublicKey;

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
    ByteArrayWrapper put(UserPublicKey sharingKey,
                         byte[] mapKey,
                         byte[] value) throws IOException;

    /**
     *
     * @param sharingKey
     * @param mapKey
     * @return  the value stored under mapKey for sharingKey
     * @throws IOException
     */
    ByteArrayWrapper get(UserPublicKey sharingKey,
                         byte[] mapKey) throws IOException;

    /**
     *
     * @param sharingKey
     * @param mapKey
     * @return  hash(sharingKey.metadata) | the new root hash of the btree
     * @throws IOException
     */
    ByteArrayWrapper remove(UserPublicKey sharingKey,
                            byte[] mapKey) throws IOException;
}
