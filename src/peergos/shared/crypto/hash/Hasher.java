package peergos.shared.crypto.hash;

import peergos.shared.crypto.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;

import java.util.concurrent.CompletableFuture;

public interface Hasher {

    CompletableFuture<byte[]> hashToKeyBytes(String username, String password, SecretGenerationAlgorithm algorithm);

    CompletableFuture<ProofOfWork> generateProofOfWork(int difficulty, byte[] data);

    CompletableFuture<byte[]> sha256(byte[] input);

    CompletableFuture<Multihash> hash(AsyncReader stream, long length);

    byte[] blake2b(byte[] input, int outputBytes);

    default CompletableFuture<Multihash> hash(byte[] input, boolean isRaw) {
        return sha256(input)
                .thenApply(h -> Cid.buildCidV1(isRaw ? Cid.Codec.Raw : Cid.Codec.DagCbor, Multihash.Type.sha2_256, h));
    }

    default CompletableFuture<Multihash> bareHash(byte[] input) {
        return sha256(input)
                .thenApply(h -> new Multihash(Multihash.Type.sha2_256, h));
    }

    default Multihash identityHash(byte[] input, boolean isRaw) {
        if (input.length > Multihash.MAX_IDENTITY_HASH_SIZE)
            throw new IllegalStateException("Exceeded maximum size for identity multihashes!");
        return Cid.buildCidV1(isRaw ? Cid.Codec.Raw : Cid.Codec.DagCbor, Multihash.Type.id, input);
    }
}
