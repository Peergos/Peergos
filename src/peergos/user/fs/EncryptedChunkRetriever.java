package peergos.user.fs;

import org.ipfs.api.*;
import peergos.crypto.symmetric.*;
import peergos.user.*;
import peergos.user.fs.erasure.*;
import peergos.util.*;

import java.io.*;
import java.util.*;
import java.util.function.*;
import java.util.stream.*;

public class EncryptedChunkRetriever implements FileRetriever {

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
    }

    public LazyInputStreamCombiner getFile(UserContext context, SymmetricKey dataKey, long len, Location ourLocation, Consumer<Long> monitor) throws IOException {
        LocatedChunk chunk = getChunkInputStream(context, dataKey, 0, len, ourLocation, monitor);
        return new LazyInputStreamCombiner(this, context, dataKey, chunk.chunk.data(), len, monitor);
    }

    public LocatedEncryptedChunk getEncryptedChunk(long bytesRemainingUntilStart, SymmetricKey dataKey, Location ourLocation, UserContext context, Consumer<Long> monitor) throws IOException {
        if (bytesRemainingUntilStart < Chunk.MAX_SIZE) {
            List<FragmentWithHash> fragments = context.downloadFragments(fragmentHashes, monitor);
            fragments = reorder(fragments, fragmentHashes);
            byte[] cipherText = Erasure.recombine(fragments.stream().map(f -> f.fragment.data).collect(Collectors.toList()),
                    Chunk.MAX_SIZE, nOriginalFragments, nAllowedFailures);
            EncryptedChunk fullEncryptedChunk = new EncryptedChunk(ArrayOps.concat(chunkAuth, cipherText));
            return new LocatedEncryptedChunk(ourLocation, fullEncryptedChunk);
        }
        FileAccess meta = context.getMetadata(getNext());
        FileRetriever nextRet = meta.retriever();
        return nextRet.getEncryptedChunk(bytesRemainingUntilStart - Chunk.MAX_SIZE, dataKey, getNext(), context, monitor);
    }

    public Location getNext() {
        return this.nextChunk;
    }

    public LocatedChunk getChunkInputStream(UserContext context, SymmetricKey dataKey, long startIndex, long truncateTo, Location ourLocation, Consumer<Long> monitor) throws IOException {
        LocatedEncryptedChunk fullEncryptedChunk = getEncryptedChunk(0, dataKey, ourLocation, context, monitor);
        if (truncateTo < Chunk.MAX_SIZE)
            fullEncryptedChunk = new LocatedEncryptedChunk(fullEncryptedChunk.location, fullEncryptedChunk.chunk.truncateTo((int)truncateTo));
        byte[] original = fullEncryptedChunk.chunk.decrypt(dataKey, chunkNonce);
        return new LocatedChunk(fullEncryptedChunk.location, new Chunk(original, dataKey));
    }

    public void serialize(DataSink buf) {
        buf.writeByte((byte)1); // This class
        buf.writeArray(chunkNonce);
        buf.writeArray(chunkAuth);
        buf.writeArray(ArrayOps.concat(fragmentHashes.stream().map(h -> new ByteArrayWrapper(h.toBytes())).collect(Collectors.toList())));
        buf.writeByte(this.nextChunk != null ? (byte)1 : 0);
        if (this.nextChunk != null)
            buf.write(this.nextChunk.serialize());
        buf.writeInt(this.nOriginalFragments);
        buf.writeInt(this.nAllowedFailures);
    }

    public static EncryptedChunkRetriever deserialize(DataSource buf) throws IOException {
        byte[] chunkNonce = buf.readArray();
        byte[] chunkAuth = buf.readArray();
        byte[] concatFragmentHashes = buf.readArray();

        List<Multihash> hashes = new ArrayList<>();
        DataSource dataSource = new DataSource(concatFragmentHashes);
        while (dataSource.remaining() != 0)
            hashes.add(Multihash.deserialize(dataSource));

        boolean hasNext = buf.readBoolean();
        Location nextChunk = null;
        if (hasNext)
            nextChunk = Location.deserialize(buf);
        int nOriginalFragments = buf.readInt();
        int nAllowedFailures = buf.readInt();
        if (!EncryptedChunk.ALLOWED_ORIGINAL.contains(nOriginalFragments) || !EncryptedChunk.ALLOWED_FAILURES.contains(nAllowedFailures)) {
            // backwards compatible with when these were not included
            buf.skip(-8);
            nOriginalFragments = EncryptedChunk.ERASURE_ORIGINAL;
            nAllowedFailures = EncryptedChunk.ERASURE_ALLOWED_FAILURES;
        }

        return new EncryptedChunkRetriever(chunkNonce, chunkAuth, hashes, nextChunk, nOriginalFragments, nAllowedFailures);
    }

    private static List<FragmentWithHash> reorder(List<FragmentWithHash> fragments, List<Multihash> hashes) {
        List<FragmentWithHash> res = new ArrayList<>();
        for (FragmentWithHash f: fragments)
            res.add(hashes.indexOf(f.hash), f);
        return res;
    }

    private static List<byte[]> split(byte[] arr, int size) {
        int length = arr.length/size;
        List<byte[]> res = new ArrayList<>();
        for (int i=0; i < length; i++)
            res.add(Arrays.copyOfRange(arr, i*size, (i+1)*size));
        return res;
    }
}
