package peergos.shared.crypto.hash;

import jsinterop.annotations.*;
import peergos.shared.crypto.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;

import java.util.concurrent.*;

@JsType(namespace = "scryptJS", isNative = true)
public class NativeScryptJS {

    public native CompletableFuture<byte[]> hashToKeyBytes(String username, String password, SecretGenerationAlgorithm algorithm);

    public native CompletableFuture<ProofOfWork> generateProofOfWork(int difficulty, byte[] data);

    public native CompletableFuture<byte[]> sha256(byte[] input);

    public native CompletableFuture<byte[]> hmacSha256(byte[] secretKey, byte[] message);

    public native byte[] blake2b(byte[] input, int outputBytes);

    public native CompletableFuture<byte[]> streamSha256(AsyncReader stream, int length);
}
