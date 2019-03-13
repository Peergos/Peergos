package peergos.shared.crypto.hash;

import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.user.*;

import java.util.concurrent.CompletableFuture;

public interface Hasher {

    CompletableFuture<byte[]> hashToKeyBytes(String username, String password, SecretGenerationAlgorithm algorithm);

    byte[] sha256(byte[] input);

    default Multihash hash(byte[] input) {
        return new Multihash(Multihash.Type.sha2_256, sha256(input));
    }

    default Multihash identityHash(byte[] input) {
        return new Multihash(Multihash.Type.id, input);
    }
}
