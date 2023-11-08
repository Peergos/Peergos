package peergos.shared.storage.auth;

import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.random.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.bases.*;
import peergos.shared.util.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

/** Block Access token
 *  This is used as a secret key in AWS S3 V4 signatures to authorise retrieving a block
 */
public class Bat implements Cborable {
    public static final byte[] RAW_BLOCK_MAGIC_PREFIX = new byte[]{113, 29, 16, -49, 61, 50, 47, 43}; // generated randomly by a fair die roll (and not valid cbor at start)
    public static final int MAX_RAW_BLOCK_PREFIX_SIZE = 100;
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
        return h.hash(serialize(), true).thenApply(BatId::new);
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
        S3Request req = new S3Request("GET", sourceNode.bareMultihash().toBase58(), "api/v0/block/get?arg=" + block.toBase58(), S3Request.UNSIGNED,
                Optional.of(expirySeconds), false, true,
                Collections.emptyMap(), Collections.emptyMap(), batId.toBase58(), "eu-central-1", datetime);
        return S3Request.computeSignature(req, encodeSecret(), h)
                .thenApply(signature -> new BlockAuth(ArrayOps.hexToBytes(signature), expirySeconds, datetime, batId));
    }

    public static byte[] createRawBlockPrefix(Bat inlineBat, Optional<BatId> mirrorBat) {
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        try {
            bout.write(RAW_BLOCK_MAGIC_PREFIX);
            List<BatId> bats = Stream.concat(Stream.of(inlineBat).map(BatId::inline), mirrorBat.stream()).collect(Collectors.toList());
            CborObject.CborList cbor = new CborObject.CborList(bats);
            bout.write(cbor.serialize());
            return bout.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static List<BatId> getBlockBats(Cid h, byte[] data) {
        return h.isRaw() ? getRawBlockBats(data) : getCborBlockBats(data);
    }

    public static List<BatId> getRawBlockBats(byte[] block) {
        int magicLength = RAW_BLOCK_MAGIC_PREFIX.length;
        if (! ArrayOps.equalArrays(block, 0, magicLength, RAW_BLOCK_MAGIC_PREFIX, 0, magicLength))
            return Collections.emptyList();
        ByteArrayInputStream bin = new ByteArrayInputStream(block);
        bin.skip(magicLength);
        return ((CborObject.CborList) CborObject.read(bin, block.length)).map(BatId::fromCbor);
    }

    public static List<BatId> getCborBlockBats(byte[] data) {
        CborObject cbor = CborObject.fromByteArray(data);
        if (! (cbor instanceof CborObject.CborMap))
            return Collections.emptyList();
        return ((CborObject.CborMap) cbor).getList("bats", BatId::fromCbor);
    }

    public static byte[] removeRawBlockBatPrefix(byte[] block) {
        int magicLength = RAW_BLOCK_MAGIC_PREFIX.length;
        if (! ArrayOps.equalArrays(block, 0, magicLength, RAW_BLOCK_MAGIC_PREFIX, 0, magicLength))
            return block;
        ByteArrayInputStream bin = new ByteArrayInputStream(block);
        bin.skip(magicLength);
        int start = magicLength + CborObject.read(bin, block.length).serialize().length;
        return Arrays.copyOfRange(block, start, block.length);
    }

    public static Bat random(SafeRandom r) {
        return new Bat(r.randomBytes(BAT_LENGTH));
    }
}
