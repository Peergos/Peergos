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
        Instant timestamp = Instant.parse(String.format("%s-%s-%sT%s:%s:%sZ", t.substring(0, 4), t.substring(4, 6), t.substring(6, 8), t.substring(9, 11), t.substring(11, 13), t.substring(13, 15)));
        Instant expiry = timestamp.plusSeconds(auth.expirySeconds);
        if (expiry.isBefore(Instant.now()))
            return false;
        String signature = S3Request.computeSignature(req, bat.encodeSecret(), h).join();
        return signature.equals(ArrayOps.bytesToHex(auth.signature));
    }
}
