package peergos.server.tests;

import org.junit.*;
import peergos.shared.merklebtree.*;

import java.io.*;
import java.util.*;

public class MerkleNodeSerialization {

    @Test
    public void emptyNode() throws IOException {
        MerkleNode original = new MerkleNode(new byte[0], Collections.emptyList());
        testMerkleNode(original);
    }

    @Test
    public void nonEmpty() throws IOException {
        byte[] data = new byte[100];
        new Random(1).nextBytes(data);
        MerkleNode original = new MerkleNode(data, Collections.emptyList());
        testMerkleNode(original);
    }

    private void testMerkleNode(MerkleNode original) throws IOException {

        byte[] cbor = original.serialize();

        MerkleNode deserialized = MerkleNode.deserialize(cbor);

        boolean equal = deserialized.equals(original);

        byte[] cbor2 = deserialized.serialize();
        boolean sameCbor = Arrays.equals(cbor, cbor2);
        Assert.assertTrue("Same deserialization", equal);
        Assert.assertTrue("Same serialization", sameCbor);
    }

}
