package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.storage.*;
import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.storage.*;

import java.util.*;

public class NonWriteThroughStorageTests {

    @Test
    public void getSizeFindsLocalModification() {
        Crypto crypto = Main.initCrypto();
        RAMStorage source = new RAMStorage(crypto.hasher);
        NonWriteThroughStorage storage = new NonWriteThroughStorage(source, crypto.hasher);
        PublicKeyHash owner = new PublicKeyHash(
                Cid.buildCidV1(Cid.Codec.DagCbor, Multihash.Type.id, crypto.random.randomBytes(36)));

        byte[] block = new CborObject.CborString("only in the local modifications").toByteArray();
        TransactionId tid = storage.startTransaction(owner).join();
        List<Cid> cids = storage.put(owner, owner, List.of(new byte[0]), List.of(block), tid).join();

        Assert.assertEquals(Optional.of(block.length), storage.getSize(owner, cids.get(0)).join());
    }
}
