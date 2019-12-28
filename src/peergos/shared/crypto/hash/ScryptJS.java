package peergos.shared.crypto.hash;

import peergos.shared.user.*;

import java.util.concurrent.CompletableFuture;

public class ScryptJS implements Hasher {

    NativeScryptJS scriptJS = new NativeScryptJS();
    
    @Override
    public CompletableFuture<byte[]> hashToKeyBytes(String username, String password, SecretGenerationAlgorithm algorithm) {
        return scriptJS.hashToKeyBytes(username, password, algorithm);
    }


    @Override
    public CompletableFuture<byte[]> sha256(byte[] input) {
        return scriptJS.sha256(input);
    }

    @Override
    public byte[] blake2b(byte[] input, int outputBytes) {
        return scriptJS.blake2b(input, outputBytes);
    }
}
