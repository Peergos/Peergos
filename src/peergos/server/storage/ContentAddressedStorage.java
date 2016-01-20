package peergos.server.storage;

import org.ipfs.api.Multihash;
import peergos.server.merklebtree.MerkleNode;

public interface ContentAddressedStorage {

    static final int MAX_OBJECT_LENGTH  = 1024*256;
    /**
     *
     * @param object
     * @return a hash of the stored object
     */
    Multihash put(MerkleNode object);

    /**
     *
     * @param value
     * @return a hash of the value
     */
    byte[] put(byte[] value);

    /**
     *
     * @param key the hash of a value previously stored
     * @return
     */
    byte[] get(byte[] key);

    /**
     *
     * @param key the hash of a value previously stored
     */
    void remove(byte[] key);
}
