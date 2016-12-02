package peergos.shared.merklebtree;

import peergos.shared.ipfs.api.*;
import peergos.shared.util.*;

import java.io.*;
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

    public MerkleNode addLink(String label, Multihash linkTarget) {
        SortedMap<String, Multihash> tmp = new TreeMap<>(links);
        tmp.put(label, linkTarget);
        return new MerkleNode(data, tmp);
    }

    public MerkleNode setData(byte[] newData) {
        return new MerkleNode(newData, links);
    }

    public byte[] serialize() {
        DataSink sink = new DataSink();
        sink.writeArray(data);
        sink.writeInt(links.size());
        for (String label: links.keySet()) {
            sink.writeString(label);
            sink.writeArray(links.get(label).toBytes());
        }
        return sink.toByteArray();
    }

    public static MerkleNode deserialize(byte[] in) throws IOException {
        DataSource source = new DataSource(in);
        byte[] data = source.readArray();
        int size = source.readInt();
        SortedMap<String, Multihash> links = new TreeMap<>();
        for (int i = 0; i < size; i++) {
            String label = source.readString();
            Multihash target = Multihash.deserialize(source);
            links.put(label, target);
        }
        return new MerkleNode(data, links);
    }
}
