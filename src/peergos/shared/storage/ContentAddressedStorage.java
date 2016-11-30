package peergos.shared.storage;

import peergos.shared.ipfs.api.Multihash;
import peergos.shared.merklebtree.MerkleNode;

import java.util.*;
import java.util.stream.*;

public interface ContentAddressedStorage {

    int MAX_OBJECT_LENGTH  = 1024*256;

    /**
     *
     * @param object
     * @return a hash of the stored object
     */
    Multihash put(MerkleNode object);

    /**
     *
     * @param key the hash of a value previously stored
     * @return
     */
    byte[] get(Multihash key);

    default Multihash put(byte[] data, List<Multihash> links) {
        return put(new MerkleNode(data, links.stream().collect(Collectors.toMap(m -> m.toString(), m -> m))));
    }

    default Multihash put(byte[] data) {
        return put(new MerkleNode(data, Collections.emptyMap()));
    }

    boolean recursivePin(Multihash h);

    boolean recursiveUnpin(Multihash h);
}
