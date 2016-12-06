package peergos.server.tests;

import org.junit.*;
import peergos.shared.merklebtree.*;

import java.io.*;
import java.util.*;

public class MerkleNodeSerialization {

    @Test
    public void inverses() throws IOException {
        MerkleNode original = new MerkleNode(new byte[0], Collections.emptyList());

        byte[] cbor = original.serialize();

        MerkleNode deserialized = MerkleNode.deserialize(cbor);

        boolean equal = deserialized.equals(original);

        byte[] cbor2 = deserialized.serialize();
        boolean sameCbor = Arrays.equals(cbor, cbor2);
        Assert.assertTrue("Same deserialization", equal);
        Assert.assertTrue("Same serialization", sameCbor);
    }
}
