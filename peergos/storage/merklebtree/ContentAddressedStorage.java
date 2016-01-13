package peergos.storage.merklebtree;

import org.ipfs.api.Multihash;
import peergos.user.fs.Fragment;
import peergos.util.ByteArrayWrapper;

import java.util.HashMap;
import java.util.Map;

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

    class Memory implements ContentAddressedStorage {
        Map<ByteArrayWrapper, Fragment> storage = new HashMap<>();

        @Override
        public Multihash put(MerkleNode object) {
            // assumes links are encoded in the data, so don't need to be explicitly serialized
            return new Multihash(put(object.data));
        }

        @Override
        public byte[] put(byte[] value) {
            Fragment f = new Fragment(value);
            storage.put(new ByteArrayWrapper(f.getHash()), f);
            return f.getHash();
        }

        @Override
        public byte[] get(byte[] key) {
            return storage.get(new ByteArrayWrapper(key)).getData();
        }

        @Override
        public void remove(byte[] key) {
            storage.remove(new ByteArrayWrapper(key));
        }
    }
}
