package peergos.server.storage.auth;

import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.storage.auth.*;
import peergos.shared.util.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

public interface BlockRequestAuthoriser {

    CompletableFuture<Boolean> allowRead(Cid block, byte[] blockData, Cid sourceNodeId, String auth);

    static boolean isValidAuth(BlockAuth auth, Cid block, Cid sourceNode, Bat bat, Hasher h) {
        String t = auth.awsDatetime;
        S3Request req = new S3Request("GET", sourceNode.toBase58(), "api/v0/block/get?arg=" + block.toBase58(), S3Request.UNSIGNED,
                Optional.of(auth.expirySeconds), false, true,
                Collections.emptyMap(), Collections.emptyMap(), auth.batId.toBase58(), "eu-central-1", t);
        LocalDateTime timestamp = auth.timestamp();
        LocalDateTime expiry = timestamp.plusSeconds(auth.expirySeconds);
        LocalDateTime now = LocalDateTime.now();
        if (expiry.isBefore(now))
            return false;
        String signature = S3Request.computeSignature(req, bat.encodeSecret(), h).join();
        String expected = ArrayOps.bytesToHex(auth.signature);
        return signature.equals(expected);
    }

    static String invalidReason(BlockAuth auth, Cid block, Cid sourceNode, Bat bat, Hasher h) {
        String t = auth.awsDatetime;
        LocalDateTime timestamp = auth.timestamp();
        LocalDateTime expiry = timestamp.plusSeconds(auth.expirySeconds);
        LocalDateTime now = LocalDateTime.now();
        // INVALID AUTH: Expired: 2022-04-19T08:05:34Z is before now: 2022-04-19T13:00:34.679482Z
        if (expiry.isBefore(now))
            return "Expired: " + expiry + " is before now: " + now;
        return "Invalid signature";
    }
}
