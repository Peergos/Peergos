package peergos.server.storage.auth;

import peergos.server.storage.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.random.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.io.ipfs.multibase.*;
import peergos.shared.util.*;

import java.nio.charset.*;
import java.security.*;
import java.time.*;
import java.util.*;

/** Block Access token
 *  This is used as a secret key in AWS S3 V4 signatures to authorise retrieving a block
 */
public class Bat implements Cborable {
    public static final int BAT_LENGTH = 32;

    public final byte[] secret;

    public Bat(byte[] secret) {
        if (secret.length != BAT_LENGTH)
            throw new IllegalStateException("Invalid BAT length: " + secret.length);
        this.secret = secret;
    }

    private String encodeSecret() {
        return Base58.encode(secret);
    }

    @Override
    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("s", new CborObject.CborByteArray(secret));
        return CborObject.CborMap.build(state);
    }

    public static Bat fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Incorrect cbor for Bat: " + cbor);

        CborObject.CborMap m = (CborObject.CborMap) cbor;
        return new Bat(m.getByteArray("s"));
    }

    public BlockAuth generateAuth(Cid block, Cid sourceNode, int expirySeconds, ZonedDateTime now, Cid batId) {
        if (batId.isIdentity())
            throw new IllegalStateException("Cannot use identity multihash in S3 signatures!");
        S3Request req = new S3Request("GET", sourceNode.toBase58(), "api/v0/block/get?arg=" + block.toBase58(), S3Request.UNSIGNED,
                Optional.of(expirySeconds), false, true,
                Collections.emptyMap(), Collections.emptyMap(), batId.toBase58(), "eu-central-1", now);
        String signature = S3Request.computeSignature(req, encodeSecret());
        return new BlockAuth(ArrayOps.hexToBytes(signature), expirySeconds, S3Request.asAwsTime(now), batId);
    }

    public boolean isValidAuth(BlockAuth auth, Cid block, Cid sourceNode) {
        String t = auth.awsDatetime;
        S3Request req = new S3Request("GET", sourceNode.toBase58(), "api/v0/block/get?arg=" + block.toBase58(), S3Request.UNSIGNED,
                Optional.of(auth.expirySeconds), false, true,
                Collections.emptyMap(), Collections.emptyMap(), auth.batId.toBase58(), "eu-central-1", auth.shortDate(), t);
        Instant timestamp = Instant.parse(String.format("%s-%s-%sT%s:%s:%sZ", t.substring(0, 4), t.substring(4, 6), t.substring(6, 8), t.substring(9, 11), t.substring(11, 13), t.substring(13, 15)));
        Instant expiry = timestamp.plusSeconds(auth.expirySeconds);
        if (expiry.isBefore(Instant.now()))
            return false;
        String signature = S3Request.computeSignature(req, encodeSecret());
        return signature.equals(ArrayOps.bytesToHex(auth.signature));
    }

    public static Bat deriveFromRawBlock(byte[] block) {
        // TODO need to analyse the cryptographic properties of this construction, might need to use hmac_sha256 instead
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update("peergos".getBytes(StandardCharsets.UTF_8));
            md.update(block);
            md.update("peergos".getBytes(StandardCharsets.UTF_8));
            return new Bat(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public static Bat random(SafeRandom r) {
        return new Bat(r.randomBytes(BAT_LENGTH));
    }
}
