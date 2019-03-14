package peergos.shared.crypto.hash;

import peergos.shared.io.ipfs.cid.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.user.*;

import java.util.concurrent.CompletableFuture;

public interface Hasher {

    CompletableFuture<byte[]> hashToKeyBytes(String username, String password, SecretGenerationAlgorithm algorithm);

    byte[] sha256(byte[] input);

    default Multihash hash(byte[] input, boolean isRaw) {
        return Cid.buildCidV1(isRaw ? Cid.Codec.Raw : Cid.Codec.DagCbor, Multihash.Type.sha2_256, sha256(input));
    }

    default Multihash identityHash(byte[] input, boolean isRaw) {
        return Cid.buildCidV1(isRaw ? Cid.Codec.Raw : Cid.Codec.DagCbor, Multihash.Type.id, input);
    }
}
