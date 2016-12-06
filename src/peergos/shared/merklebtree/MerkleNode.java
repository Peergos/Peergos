package peergos.shared.merklebtree;

import peergos.shared.cbor.*;
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

    /**
     *
     * @return a byte[] of the CBOR serialization of this merkle node
     */
    public byte[] serialize() {
        try {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            CborEncoder encoder = new CborEncoder(bout);
            encoder.writeByteString(data);
            encoder.writeInt8(links.size());
            for (String label : links.keySet()) {
                encoder.writeTextString(label);
                encoder.writeByteString(links.get(label).toBytes());
            }
            return bout.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     *
     * @param in a CBOR encoding of a merkle node
     * @return
     * @throws IOException for an invalid encoding
     */
    public static MerkleNode deserialize(byte[] in) throws IOException {
        CborDecoder decoder = new CborDecoder(new ByteArrayInputStream(in));
        byte[] data = decoder.readByteString();
        SortedMap<String, Multihash> links = new TreeMap<>();
        int nLinks = decoder.readInt8();
        for (int i = 0; i < nLinks; i++) {
            String label = decoder.readTextString();
            Multihash target = Multihash.deserialize(new DataSource(decoder.readByteString()));
            links.put(label, target);
        }
        return new MerkleNode(data, links);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        MerkleNode that = (MerkleNode) o;

        if (!Arrays.equals(data, that.data)) return false;
        return links != null ? links.equals(that.links) : that.links == null;

    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(data);
        result = 31 * result + (links != null ? links.hashCode() : 0);
        return result;
    }
}
