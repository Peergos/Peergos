package peergos.server.storage;

import org.ipfs.api.Multihash;
import peergos.server.merklebtree.MerkleNode;

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

    default Multihash put(byte[] data, List<Multihash> links) {
        return put(new MerkleNode(data, links.stream().collect(Collectors.toMap(m -> m.toString(), m -> m))));
    }

    /**
     *
     * @param key the hash of a value previously stored
     * @return
     */
    byte[] get(Multihash key);

    /**
     *
     * @param key the hash of a value previously stored
     */
    void remove(Multihash key);

    boolean recursivePin(Multihash h);

    boolean recursiveUnpin(Multihash h);
}
