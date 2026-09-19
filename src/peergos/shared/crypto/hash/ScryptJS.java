package peergos.shared.crypto.hash;

import peergos.shared.crypto.*;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;

import java.util.Optional;
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
    public CompletableFuture<byte[]> hmacSha256(byte[] secretKey, byte[] message) {
        return scriptJS.hmacSha256(secretKey, message);
    }

    @Override
    public byte[] blake2b(byte[] input, int outputBytes) {
        return scriptJS.blake2b(input, outputBytes);
    }

    @Override
    public CompletableFuture<byte[]> sha256Section(AsyncReader reader, long start, long end) {
        if (reader instanceof BrowserFileReader) {
            JSFileReader jsReader = ((BrowserFileReader) reader).getReader();
            return scriptJS.sha256FileSection(jsReader,
                    (int)(start >> 32), (int)start, (int)(end >> 32), (int)end);
        }
        return Hasher.super.sha256Section(reader, start, end);
    }

    @Override
    public CompletableFuture<byte[]> blake3(byte[] input) {
        return scriptJS.blake3(input);
    }

    @Override
    public CompletableFuture<byte[]> blake3ChainingValue(byte[] input, long startChunk) {
        return scriptJS.blake3ChainingValue(input, (int) (startChunk >> 32), (int) startChunk);
    }

    @Override
    public CompletableFuture<byte[]> blake3Section(AsyncReader reader, long start, long end) {
        return blake3FileSection(reader, start, end, false, 0)
                .orElseGet(() -> Hasher.super.blake3Section(reader, start, end));
    }

    @Override
    public CompletableFuture<byte[]> blake3SectionChainingValue(AsyncReader reader, long start, long end, long startChunk) {
        return blake3FileSection(reader, start, end, true, startChunk)
                .orElseGet(() -> Hasher.super.blake3SectionChainingValue(reader, start, end, startChunk));
    }

    /** Hash a slice of the file in a worker, when the reader is backed by a real file. */
    private Optional<CompletableFuture<byte[]>> blake3FileSection(AsyncReader reader,
                                                                  long start,
                                                                  long end,
                                                                  boolean asChainingValue,
                                                                  long startChunk) {
        if (! (reader instanceof BrowserFileReader))
            return Optional.empty();
        JSFileReader jsReader = ((BrowserFileReader) reader).getReader();
        return Optional.of(scriptJS.blake3FileSection(jsReader,
                (int) (start >> 32), (int) start, (int) (end >> 32), (int) end,
                asChainingValue, (int) (startChunk >> 32), (int) startChunk));
    }

    @Override
    @SuppressWarnings("unusable-by-js")
    public CompletableFuture<Multihash> hashFromStream(AsyncReader stream, long length) {
        return scriptJS.streamSha256(stream, (int) length)
                .thenApply(h -> new Multihash(Multihash.Type.sha2_256, h));
    }
}
