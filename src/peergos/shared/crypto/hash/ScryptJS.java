package peergos.shared.crypto.hash;

import java.util.concurrent.CompletableFuture;

public class ScryptJS implements LoginHasher {

    NativeScryptJS scriptJS = new NativeScryptJS();
    
    @Override
    public CompletableFuture<byte[]> hashToKeyBytes(String username, String password) {
        return scriptJS.hashToKeyBytes(username, password);
    }
}
