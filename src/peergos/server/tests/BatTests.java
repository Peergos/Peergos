package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.storage.auth.*;
import peergos.shared.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.random.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.io.ipfs.multihash.*;

import java.time.*;

public class BatTests {
    private static final Crypto crypto = Main.initCrypto();
    private static final SafeRandom rnd = crypto.random;
    private static final Hasher hasher = crypto.hasher;

    @Test
    public void roundtripAndValidity() {
        Cid block = hasher.hash(rnd.randomBytes(100), false).join();
        Bat bat = Bat.random(rnd);
        Cid batId = hasher.hash(bat.serialize(), false).join();
        Cid nodeId = new Cid(1, Cid.Codec.LibP2pKey, Multihash.Type.id, rnd.randomBytes(32));

        ZonedDateTime now = ZonedDateTime.now();
        BlockAuth auth = bat.generateAuth(block, nodeId, 300, now, batId);

        String forBitswap = auth.encode();
        BlockAuth receivedAuth = BlockAuth.fromString(forBitswap);
        boolean validAuth = bat.isValidAuth(receivedAuth, block, nodeId);
        Assert.assertTrue(validAuth);

        // invalid signature
        BlockAuth invalidSig = new BlockAuth(new byte[32], auth.expirySeconds, auth.awsDatetime, batId);
        Assert.assertTrue(!bat.isValidAuth(invalidSig, block, nodeId));

        // invalid date
        String differentDatetime = bat.generateAuth(block, nodeId, 300, now.plusSeconds(1), batId).awsDatetime;
        BlockAuth invalidDate = new BlockAuth(auth.signature, 300, differentDatetime, batId);
        Assert.assertTrue(!bat.isValidAuth(invalidDate, block, nodeId));

        // expired sig
        BlockAuth expired = bat.generateAuth(block, nodeId, 300, now.minusSeconds(301), batId);
        Assert.assertTrue(!bat.isValidAuth(expired, block, nodeId));
    }
}
