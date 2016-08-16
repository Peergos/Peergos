package peergos.shared.merklebtree;

import peergos.shared.ipfs.api.*;

import java.util.Collections;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

public class MerkleNode {
    public final byte[] data;
    public final SortedMap<String, Multihash> links;

    public MerkleNode(byte[] data, Map<String, Multihash> links) {
        this.data = data;
        this.links = new TreeMap<>(links);
    }

    public MerkleNode(byte[] data) {
        this(data, Collections.EMPTY_MAP);
    }
}
