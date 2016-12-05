package peergos.shared.merklebtree;

import peergos.shared.ipfs.api.*;
import peergos.shared.util.*;

import java.io.*;
import java.util.*;

public class MerkleNode {
    public final byte[] data;
    public final SortedMap<String, Multihash> links;

    public MerkleNode(byte[] data, Map<String, Multihash> links) {
        this.data = data;
        this.links = new TreeMap<>(links);
    }

    public MerkleNode(byte[] data) {
        this(data, Collections.emptyMap());
    }

    public MerkleNode addLink(String label, Multihash linkTarget) {
        SortedMap<String, Multihash> tmp = new TreeMap<>(links);
        tmp.put(label, linkTarget);
        return new MerkleNode(data, tmp);
    }

    public MerkleNode setData(byte[] newData) {
        return new MerkleNode(newData, links);
    }

    public String toJson(Optional<Multihash> ourHash) {
        Map result = new TreeMap();
        if (ourHash.isPresent())
            result.put("Hash", ourHash.get().toBase58());
        result.put("Data", new String(data));
        List<Object> linksList = new ArrayList<>();
        links.entrySet().forEach(e -> {
            Map linkNode = new TreeMap();
            linkNode.put("Name", e.getKey());
            linkNode.put("Hash", e.getValue().toBase58());
            linksList.add(linkNode);
        });
        result.put("Links", linksList);
        return JSONParser.toString(result);
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
