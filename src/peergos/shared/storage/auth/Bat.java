package peergos.shared.storage.auth;

import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.random.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.io.ipfs.multibase.*;
import peergos.shared.util.*;

import java.nio.charset.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

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

    public String encodeSecret() {
        return Multibase.encode(Multibase.Base.Base58BTC, secret);
    }

    public static Bat fromString(String encoded) {
        return new Bat(Multibase.decode(encoded));
    }

    public CompletableFuture<BatId> calculateId(Hasher h) {
        return h.hash(serialize(), false).thenApply(BatId::new);
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

    @Override
    public int hashCode() {
        return Arrays.hashCode(secret);
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof Bat))
            return false;
        return Arrays.equals(secret, ((Bat) obj).secret);
    }

    public CompletableFuture<BlockAuth> generateAuth(Cid block,
                                                     Cid sourceNode,
                                                     int expirySeconds,
                                                     String datetime,
                                                     Cid batId,
                                                     Hasher h) {
        if (batId.isIdentity())
            throw new IllegalStateException("Cannot use identity multihash in S3 signatures!");
        S3Request req = new S3Request("GET", sourceNode.toBase58(), "api/v0/block/get?arg=" + block.toBase58(), S3Request.UNSIGNED,
                Optional.of(expirySeconds), false, true,
                Collections.emptyMap(), Collections.emptyMap(), batId.toBase58(), "eu-central-1", datetime);
        return S3Request.computeSignature(req, encodeSecret(), h)
                .thenApply(signature -> new BlockAuth(ArrayOps.hexToBytes(signature), expirySeconds, datetime, batId));
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
