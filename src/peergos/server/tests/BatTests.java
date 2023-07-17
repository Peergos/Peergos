package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.storage.*;
import peergos.shared.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.random.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.storage.auth.*;

import java.time.*;

import static peergos.server.storage.auth.BlockRequestAuthoriser.isValidAuth;

public class BatTests {
    private static final Crypto crypto = Main.initCrypto();
    private static final SafeRandom rnd = crypto.random;
    private static final Hasher h = crypto.hasher;

    @Test
    public void roundtripAndValidity() {
        Cid block = h.hash(rnd.randomBytes(100), false).join();
        Bat bat = Bat.random(rnd);
        Cid batId = h.hash(bat.serialize(), false).join();
        Cid nodeId = new Cid(1, Cid.Codec.LibP2pKey, Multihash.Type.id, rnd.randomBytes(32));

        ZonedDateTime now = ZonedDateTime.now();
        String awsNow = S3AdminRequests.asAwsDate(now);
        BlockAuth auth = bat.generateAuth(block, nodeId, 300, awsNow, batId, h).join();

        String forBitswap = auth.encode();
        BlockAuth receivedAuth = BlockAuth.fromString(forBitswap);
        boolean validAuth = isValidAuth(receivedAuth, block, nodeId, bat, h);
        Assert.assertTrue(validAuth);

        // invalid signature
        BlockAuth invalidSig = new BlockAuth(new byte[32], auth.expirySeconds, auth.awsDatetime, batId);
        Assert.assertTrue(! isValidAuth(invalidSig, block, nodeId, bat, h));

        // invalid date
        String differentDatetime = bat.generateAuth(block, nodeId, 300,
                S3AdminRequests.asAwsDate(now.plusSeconds(1)), batId, h).join().awsDatetime;
        BlockAuth invalidDate = new BlockAuth(auth.signature, 300, differentDatetime, batId);
        Assert.assertTrue(! isValidAuth(invalidDate, block, nodeId, bat, h));

        // expired sig
        BlockAuth expired = bat.generateAuth(block, nodeId, 300,
                S3AdminRequests.asAwsDate(now.minusSeconds(301)), batId, h).join();
        Assert.assertTrue(! isValidAuth(expired, block, nodeId, bat, h));
    }
}
