package peergos.shared.crypto.hash;

import jsinterop.annotations.JsType;
import peergos.shared.crypto.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.storage.auth.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.nio.charset.*;
import java.util.concurrent.CompletableFuture;
@JsType
public interface Hasher {

    CompletableFuture<byte[]> hashToKeyBytes(String username, String password, SecretGenerationAlgorithm algorithm);

    CompletableFuture<ProofOfWork> generateProofOfWork(int difficulty, byte[] data);

    CompletableFuture<byte[]> sha256(byte[] input);

    default CompletableFuture<byte[]> sha256Section(AsyncReader reader, long start, long end) {
        int length = (int)(end - start);
        byte[] buf = new byte[length];
        return reader.seek(start)
                .thenCompose(seeked -> readFully(seeked, buf, 0, length))
                .thenCompose(this::sha256);
    }

    /**
     * The BLAKE3 hash of a whole input: the bytes b3sum prints for it.
     *
     * A default rather than an abstract method because {@link Blake3} is shared code that gwt
     * compiles, so every implementation has this for free. An implementation with something
     * faster - a browser with a wasm build, say - can still override it.
     */
    default CompletableFuture<byte[]> blake3(byte[] input) {
        return Futures.of(Blake3.hash(input));
    }

    /** The BLAKE3 hash of a section of a stream. */
    default CompletableFuture<byte[]> blake3Section(AsyncReader reader, long start, long end) {
        int length = (int) (end - start);
        byte[] buf = new byte[length];
        return reader.seek(start)
                .thenCompose(seeked -> readFully(seeked, buf, 0, length))
                .thenCompose(this::blake3);
    }

    /**
     * The chaining value of one piece of a file, rather than a finished hash, so that pieces
     * hashed independently can be merged into the hash of the whole file with
     * {@link Blake3#mergeNonRoot} and {@link Blake3#mergeRoot}.
     *
     * @param startChunk the index of this piece's first 1KiB BLAKE3 chunk within the file, i.e.
     *                   its byte offset divided by {@value Blake3#CHUNK_SIZE}. The piece must
     *                   begin on a chunk boundary, and be either an aligned subtree or the last
     *                   piece of the file.
     */
    default CompletableFuture<byte[]> blake3ChainingValue(byte[] input, long startChunk) {
        return Futures.of(Blake3.tailChainingValue(input, 0, input.length, startChunk));
    }

    /** The chaining value of a section of a stream, for hashing a file piece by piece. */
    default CompletableFuture<byte[]> blake3SectionChainingValue(AsyncReader reader, long start, long end, long startChunk) {
        int length = (int) (end - start);
        byte[] buf = new byte[length];
        return reader.seek(start)
                .thenCompose(seeked -> readFully(seeked, buf, 0, length))
                .thenCompose(bytes -> blake3ChainingValue(bytes, startChunk));
    }

    private static CompletableFuture<byte[]> readFully(AsyncReader reader, byte[] buf, int offset, int remaining) {
        if (remaining == 0)
            return CompletableFuture.completedFuture(buf);
        return reader.readIntoArray(buf, offset, remaining)
                .thenCompose(n -> readFully(reader, buf, offset + n, remaining - n));
    }

    CompletableFuture<byte[]> hmacSha256(byte[] secretKey, byte[] message);

    @SuppressWarnings("unusable-by-js")
    CompletableFuture<Multihash> hashFromStream(AsyncReader stream, long length);

    byte[] blake2b(byte[] input, int outputBytes);

    default CompletableFuture<Cid> hash(byte[] input, boolean isRaw) {
        return sha256(input)
                .thenApply(h -> Cid.buildCidV1(isRaw ? Cid.Codec.Raw : Cid.Codec.DagCbor, Multihash.Type.sha2_256, h));
    }

    default CompletableFuture<Multihash> bareHash(byte[] input) {
        return sha256(input)
                .thenApply(h -> new Multihash(Multihash.Type.sha2_256, h));
    }

    byte[] hmacInfo = ArrayOps.concat("peergos".getBytes(StandardCharsets.UTF_8), new byte[]{1});

    default CompletableFuture<byte[]> hkdfKey(byte[] ikm) {
        // See https://soatok.blog/2021/11/17/understanding-hkdf/ for why salt is the secret key to hmac
        byte[] salt = new byte[32];
        return hmacSha256(salt, ikm)
                .thenCompose(prk -> hmacSha256(prk, hmacInfo));
    }

    default Cid identityHash(byte[] input, boolean isRaw) {
        if (input.length > Multihash.MAX_IDENTITY_HASH_SIZE)
            throw new IllegalStateException("Exceeded maximum size for identity multihashes!");
        return Cid.buildCidV1(isRaw ? Cid.Codec.Raw : Cid.Codec.DagCbor, Multihash.Type.id, input);
    }
}
