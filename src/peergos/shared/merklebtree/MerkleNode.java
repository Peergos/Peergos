package peergos.shared.merklebtree;

import peergos.shared.cbor.*;
import peergos.shared.ipfs.api.*;
import peergos.shared.util.*;

import java.io.*;
import java.util.*;

public class MerkleNode {
    public final byte[] data;
    public final List<Link> links;

    public MerkleNode(byte[] data, List<Link> links) {
        this.data = data;
        this.links = links;
        Collections.sort(this.links);
    }

    public MerkleNode(byte[] data) {
        this(data, Collections.emptyList());
    }

    public static class Link implements Comparable<Link> {
        public final String label;
        public final Multihash target;

        public Link(String label, Multihash target) {
            this.label = label;
            this.target = target;
        }

        @Override
        public int compareTo(Link link) {
            return label.compareTo(link.label);
        }
    }

    public MerkleNode addLink(String label, Multihash linkTarget) {
        List<Link> tmp = new ArrayList<>(links);
        tmp.add(new Link(label, linkTarget));
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
            encoder.writeMapStart(1 + links.size());
            encoder.writeTextString("Data");
            encoder.writeByteString(data);
            for (Link link: links) {
                encoder.writeTextString(link.label);
                encoder.writeByteString(link.target.toBytes());
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
        long elements = decoder.readMapLength();
        String dataLabel = decoder.readTextString();
        byte[] data = decoder.readByteString();
        List<Link> links = new ArrayList<>();
        for (int i = 0; i < elements - 1; i++) {
            String label = decoder.readTextString();
            Multihash target = Multihash.deserialize(new DataSource(decoder.readByteString()));
            links.add(new Link(label, target));
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
