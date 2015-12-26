package peergos.storage.merklebtree;

import org.ipfs.api.Multihash;

public interface ContentAddressedStorage {

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
