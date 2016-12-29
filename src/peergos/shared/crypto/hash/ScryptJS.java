package peergos.shared.crypto.hash;

import peergos.shared.user.*;

import java.util.concurrent.CompletableFuture;

public class ScryptJS implements LoginHasher {

    NativeScryptJS scriptJS = new NativeScryptJS();
    
    @Override
    public CompletableFuture<byte[]> hashToKeyBytes(String username, String password, UserGenerationAlgorithm algorithm) {
        return scriptJS.hashToKeyBytes(username, password, algorithm);
    }
}
