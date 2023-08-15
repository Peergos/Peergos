package peergos.server.tests;

import org.junit.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.io.ipfs.bases.*;

import java.util.*;

public class CidTests {

    @Test
    public void nodeid() {
        byte[] rawPublicKey = Multibase.decode("mCAESIA2UgZjCUFpg3P2C4EC+kFNq9KwzTVTpTHu51Y7fQAFg");
        String peerID = "12D3KooWAjNorDWXZJx8Jhzrq9onKbrmYf9XSmteb4yKnbXfSD8K";
        byte[] rawPeerId = Base58.decode(peerID);
        Assert.assertTrue(Arrays.equals(rawPublicKey, Arrays.copyOfRange(rawPeerId, 2, rawPeerId.length)));

        // convert identity multihash to cidV1
        Multihash hash = Multihash.decode(Base58.decode(peerID));
        Cid cid = new Cid(1, Cid.Codec.LibP2pKey, hash.type, hash.getHash());
    }
}
