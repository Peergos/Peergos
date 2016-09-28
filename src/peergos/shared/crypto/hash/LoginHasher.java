package peergos.shared.crypto.hash;

import java.util.concurrent.CompletableFuture;

public interface LoginHasher {

    public CompletableFuture<byte[]> hashToKeyBytes(String username, String password);
}
