package peergos.shared.user.fs;

import peergos.shared.crypto.symmetric.SymmetricKey;

import java.util.*;
import java.util.concurrent.CompletableFuture;

public class Chunk {

    /** The chunk size of every file written before the size became a property of the file. */
    public static final int LEGACY_SIZE = 5 * 1024 * 1024;
    /**
     * The chunk size for new files. 4 MiB is 4096 BLAKE3 chunks, a power of two, so each chunk
     * of a file is a whole subtree of the file's BLAKE3 tree and the file's root hash is its
     * real BLAKE3 hash. 5 MiB is 5120 chunks and never a subtree of anything.
     */
    public static final int DEFAULT_SIZE = 4 * 1024 * 1024;

    /**
     * Whether a file with this chunk size has a BLAKE3 tree rather than the legacy sha256 one.
     *
     * The chunk size carries both facts: the two always change together, because a BLAKE3 root is
     * only the file's real BLAKE3 hash at a subtree aligned chunk size, and a power of two chunk
     * size is only worth having for BLAKE3.
     */
    public static boolean usesBlake3(int chunkSize) {
        return chunkSize != LEGACY_SIZE;
    }

    private final SymmetricKey dataKey;
    private final byte[] data, mapKey;
    private final byte[] nonce;

    public Chunk(byte[] data, SymmetricKey dataKey, byte[] mapKey, byte[] nonce) {
        this.data = data;
        this.dataKey = dataKey;
        this.mapKey = mapKey;
        this.nonce = nonce;
    }

    public SymmetricKey key() {
        return dataKey;
    }

    public byte[] mapKey() {
        return Arrays.copyOfRange(mapKey, 0, mapKey.length);
    }

    public byte[] nonce() {
        return Arrays.copyOfRange(nonce, 0, nonce.length);
    }

    public byte[] data() {
        return data;
    }

    public int length() {
        return data.length;
    }
}
