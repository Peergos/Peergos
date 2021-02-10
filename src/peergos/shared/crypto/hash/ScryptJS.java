package peergos.shared.crypto.hash;

import peergos.shared.crypto.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;

import java.util.concurrent.CompletableFuture;

public class ScryptJS implements Hasher {

    NativeScryptJS scriptJS = new NativeScryptJS();
    
    @Override
    public CompletableFuture<byte[]> hashToKeyBytes(String username, String password, SecretGenerationAlgorithm algorithm) {
        return scriptJS.hashToKeyBytes(username, password, algorithm);
    }

    @Override
    public CompletableFuture<ProofOfWork> generateProofOfWork(int difficulty, byte[] data) {
        return scriptJS.generateProofOfWork(difficulty, data);
    }

    @Override
    public CompletableFuture<byte[]> sha256(byte[] input) {
        return scriptJS.sha256(input);
    }

    @Override
    public byte[] blake2b(byte[] input, int outputBytes) {
        return scriptJS.blake2b(input, outputBytes);
    }

    @Override
    public CompletableFuture<Multihash> hash(AsyncReader stream, long length) {
        return scriptJS.streamSha256(stream, length)
                .thenApply(h -> new Multihash(Multihash.Type.sha2_256, h));
    }
}
