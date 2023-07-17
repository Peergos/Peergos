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

    default CompletableFuture<Bat> hkdfKey(byte[] data) {
        byte[] salt = new byte[32];
        return hmacSha256(salt, data)
                .thenCompose(prk -> hmacSha256(prk, hmacInfo))
                .thenApply(Bat::new);
    }

    default Cid identityHash(byte[] input, boolean isRaw) {
        if (input.length > Multihash.MAX_IDENTITY_HASH_SIZE)
            throw new IllegalStateException("Exceeded maximum size for identity multihashes!");
        return Cid.buildCidV1(isRaw ? Cid.Codec.Raw : Cid.Codec.DagCbor, Multihash.Type.id, input);
    }
}
