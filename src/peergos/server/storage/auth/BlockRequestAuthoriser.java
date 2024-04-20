package peergos.server.storage.auth;

import peergos.server.util.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.storage.auth.*;
import peergos.shared.util.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

public interface BlockRequestAuthoriser {

    default CompletableFuture<Boolean> allowRead(Cid block, byte[] blockData, Cid sourceNodeId, String auth) {
        List<BatId> batIds = block.isRaw() ?
                Bat.getRawBlockBats(blockData) :
                block.codec == Cid.Codec.DagCbor ?
                        Bat.getCborBlockBats(blockData) :
                        Collections.emptyList();
        return allowRead(block, batIds, sourceNodeId, auth);
    }

    CompletableFuture<Boolean> allowRead(Cid block, List<BatId> blockBats, Cid sourceNodeId, String auth);

    static boolean allowRead(Cid b,
                             List<BatId> batids,
                             Cid s,
                             Optional<BlockAuth> auth,
                             BatCave batStore,
                             Optional<BatWithId> instanceBat,
                             Hasher h) {
        Logging.LOG().fine("Allow: " + b + ", auth=" + auth + ", from: " + s);
        if (batids.isEmpty()) // public block
            return true;
        if (auth.isEmpty()) {
            Logging.LOG().info("INVALID AUTH: EMPTY");
            return false;
        }
        BlockAuth blockAuth = auth.get();
        if (b.isRaw()) {
            for (BatId bid : batids) {
                Optional<Bat> bat = bid.getInline()
                        .or(() -> bid.id.equals(blockAuth.batId) ?
                                batStore.getBat(bid) :
                                Optional.empty());
                if (bat.isPresent() && BlockRequestAuthoriser.isValidAuth(blockAuth, b, s, bat.get(), h))
                    return true;
            }
            if (instanceBat.isPresent()) {
                if (BlockRequestAuthoriser.isValidAuth(blockAuth, b, s, instanceBat.get().bat, h))
                    return true;
            }
            String reason = BlockRequestAuthoriser.invalidReason(blockAuth, b, s, batids, h);
            Logging.LOG().info("INVALID RAW BLOCK AUTH: source: " + s + ", cid: " + b + " reason: " + reason);
            return false;
        } else if (b.codec == Cid.Codec.DagCbor) {
            for (BatId bid : batids) {
                Optional<Bat> bat = bid.getInline()
                        .or(() -> bid.id.equals(blockAuth.batId) ?
                                batStore.getBat(bid) :
                                Optional.empty());
                if (bat.isPresent() && BlockRequestAuthoriser.isValidAuth(blockAuth, b, s, bat.get(), h))
                    return true;
            }
            if (instanceBat.isPresent()) {
                if (BlockRequestAuthoriser.isValidAuth(blockAuth, b, s, instanceBat.get().bat, h))
                    return true;
            }
            if (! batids.isEmpty()) {
                String reason = BlockRequestAuthoriser.invalidReason(blockAuth, b, s, batids, h);
                Logging.LOG().info("INVALID AUTH: source: " + s + ", cid: " + b + " reason: " + reason);
            }
            return false;
        }
        return false;
    }

    static boolean isValidAuth(BlockAuth auth, Cid block, Cid sourceNode, Bat bat, Hasher h) {
        S3Request req = new S3Request("GET", sourceNode.bareMultihash().toBase58(), "api/v0/block/get?arg=" + block.toBase58(), S3Request.UNSIGNED,
                Optional.of(auth.expirySeconds), false, true,
                Collections.emptyMap(), Collections.emptyMap(), auth.batId.toBase58(), "eu-central-1", auth.awsDatetime);
        LocalDateTime timestamp = auth.timestamp();
        LocalDateTime expiry = timestamp.plusSeconds(auth.expirySeconds);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (expiry.isBefore(now))
            return false;
        String signature = S3Request.computeSignature(req, bat.encodeSecret(), h).join();
        String expected = ArrayOps.bytesToHex(auth.signature);
        return signature.equals(expected);
    }

    static String invalidReason(BlockAuth auth, Cid block, Cid sourceNode, List<BatId> batids, Hasher h) {
        // careful here to avoid a timing attack on inline bats
        Optional<BatId> match = batids.stream()
                .filter(bid -> (! bid.isInline() && bid.id.equals(auth.batId)) ||
                        (bid.isInline() && auth.batId.equals(h.hash(bid.getInline().get().serialize(), false).join())))
                .findFirst();
        if (match.isEmpty())
            return "No matching BAT ID in block for " + auth.batId;

        LocalDateTime timestamp = auth.timestamp();
        LocalDateTime expiry = timestamp.plusSeconds(auth.expirySeconds);
        LocalDateTime now = LocalDateTime.now();
        // INVALID AUTH: Expired: 2022-04-19T08:05:34Z is before now: 2022-04-19T13:00:34.679482Z
        if (expiry.isBefore(now))
            return "Expired: " + expiry + " is before now: " + now;
        return "Invalid signature";
    }
}
