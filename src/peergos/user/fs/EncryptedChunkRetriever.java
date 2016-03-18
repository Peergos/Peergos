package peergos.user.fs;

import org.ipfs.api.*;
import peergos.crypto.*;
import peergos.crypto.symmetric.*;
import peergos.user.*;
import peergos.user.fs.erasure.*;
import peergos.util.*;

import java.io.*;
import java.util.*;
import java.util.function.*;
import java.util.stream.*;

public class EncryptedChunkRetriever {

    private final byte[] chunkNonce, chunkAuth;
    private final int nOriginalFragments, nAllowedFailures;
    private final List<Multihash> fragmentHashes;
    private final Location nextChunk;

    public EncryptedChunkRetriever(byte[] chunkNonce, byte[] chunkAuth, List<Multihash> fragmentHashes, Location nextChunk, int nOriginalFragments, int nAllowedFailures) {
        this.chunkNonce = chunkNonce;
        this.chunkAuth = chunkAuth;
        this.nOriginalFragments = nOriginalFragments;
        this.nAllowedFailures = nAllowedFailures;
        this.fragmentHashes = fragmentHashes;
        this.nextChunk = nextChunk;
        this.getFile = function(context, dataKey, len, setProgressPercentage) {
            const stream = this;
            return this.getChunkInputStream(context, dataKey, len, setProgressPercentage).then(function(chunk) {
                return Promise.resolve(new LazyInputStreamCombiner(stream, context, dataKey, chunk, setProgressPercentage));
            });
        }
    }

    public Location getNext() {
        return this.nextChunk;
    }

    public byte[] getChunkInputStream(UserContext context, SymmetricKey dataKey, int len, Consumer<Long> monitor) {
        List<Fragment> fragments = context.downloadFragments(fragmentHashes, monitor);
        fragments = reorder(fragments, fragmentHashes);
        byte[] cipherText = Erasure.recombine(fragments, len != 0 ? len : Chunk.MAX_SIZE, nOriginalFragments, nAllowedFailures);
        if (len != 0)
            cipherText = Arrays.copyOfRange(cipherText, 0, len);
        EncryptedChunk fullEncryptedChunk = new EncryptedChunk(ArrayOps.concat(chunkAuth, cipherText));
        byte[] original = fullEncryptedChunk.decrypt(dataKey, chunkNonce);
        return original;
    }

    public void serialize(DataOutputStream buf) throws IOException {
        buf.writeByte(1); // This class
        buf.writeArray(chunkNonce);
        buf.writeArray(chunkAuth);
        buf.writeArray(ArrayOps.concat(fragmentHashes));
        buf.writeByte(this.nextChunk != null ? 1 : 0);
        if (this.nextChunk != null)
            buf.write(this.nextChunk.serialize());
        buf.writeInt(this.nOriginalFragments);
        buf.writeInt(this.nAllowedFailures);
    }

    public static EncryptedChunkRetriever deserialize(DataInputStream buf) {
        byte[] chunkNonce = buf.readArray();
        byte[] chunkAuth = buf.readArray();
        byte[] concatFragmentHashes = buf.readArray();
        List<byte[]> fragmentHashes = split(concatFragmentHashes, UserPublicKey.HASH_BYTES);
        boolean hasNext = buf.readBoolean();
        Location nextChunk = null;
        if (hasNext)
            nextChunk = Location.deserialize(buf);
        int nOriginalFragments = buf.readInt();
        int nAllowedFailures = buf.readInt();
        if (!EncryptedChunk.ALLOWED_ORIGINAL.includes(nOriginalFragments) || !EncryptedChunk.ALLOWED_FAILURES.includes()) {
            // backwards compatible with when these were not included
            buf.skip(-8);
            nOriginalFragments = EncryptedChunk.ERASURE_ORIGINAL;
            nAllowedFailures = EncryptedChunk.ERASURE_ALLOWED_FAILURES;
        }
        List<Multihash> hashes = fragmentHashes.stream().map(b -> new Multihash(b)).collect(Collectors.toList());
        return new EncryptedChunkRetriever(chunkNonce, chunkAuth, hashes, nextChunk, nOriginalFragments, nAllowedFailures);
    }

    private static List<byte[]> split(byte[] arr, int size) {
        int length = arr.length/size;
        List<byte[]> res = new ArrayList<>();
        for (int i=0; i < length; i++)
            res.add(Arrays.copyOfRange(arr, i*size, (i+1)*size));
        return res;
    }
}
