package peergos.shared.crypto.hash;

import peergos.shared.user.*;

import java.util.concurrent.CompletableFuture;

public interface LoginHasher {

    CompletableFuture<byte[]> hashToKeyBytes(String username, String password, UserGenerationAlgorithm algorithm);
}
