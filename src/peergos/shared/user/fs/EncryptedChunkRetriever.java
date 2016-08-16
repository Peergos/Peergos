package peergos.shared.user.fs;

import peergos.shared.crypto.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.ipfs.api.Multihash;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.io.*;
import java.util.*;
import java.util.function.*;
import java.util.stream.*;

public class EncryptedChunkRetriever implements FileRetriever {

    private final byte[] chunkNonce, chunkAuth;
    private final List<Multihash> fragmentHashes;
    private final Location nextChunk;
    private final peergos.shared.user.fs.Fragmenter fragmenter;

    public EncryptedChunkRetriever(byte[] chunkNonce, byte[] chunkAuth, List<Multihash> fragmentHashes, Location nextChunk, Fragmenter fragmenter) {
        this.chunkNonce = chunkNonce;
        this.chunkAuth = chunkAuth;
        this.fragmentHashes = fragmentHashes;
        this.nextChunk = nextChunk;
        this.fragmenter = fragmenter;
    }

    public LazyInputStreamCombiner getFile(UserContext context, SymmetricKey dataKey, long fileSize, Location ourLocation, Consumer<Long> monitor) throws IOException {
        Optional<LocatedChunk> chunk = getChunkInputStream(context, dataKey, 0, fileSize, ourLocation, monitor);
        return new LazyInputStreamCombiner(this, context, dataKey, chunk.get().chunk.data(), fileSize, monitor);
    }

    public Optional<LocatedEncryptedChunk> getEncryptedChunk(long bytesRemainingUntilStart, long truncateTo, byte[] nonce, SymmetricKey dataKey, Location ourLocation, UserContext context, Consumer<Long> monitor) throws IOException {
        if (bytesRemainingUntilStart < Chunk.MAX_SIZE) {
            List<FragmentWithHash> fragments = context.downloadFragments(fragmentHashes, monitor);
            fragments = reorder(fragments, fragmentHashes);
            byte[][] collect = fragments.stream().map(f -> f.fragment.data).toArray(byte[][]::new);
            byte[] cipherText = fragmenter.recombine(collect, Chunk.MAX_SIZE);
            EncryptedChunk fullEncryptedChunk = new EncryptedChunk(ArrayOps.concat(chunkAuth, cipherText));
            if (truncateTo < Chunk.MAX_SIZE)
                fullEncryptedChunk = fullEncryptedChunk.truncateTo((int)truncateTo);
            return Optional.of(new LocatedEncryptedChunk(ourLocation, fullEncryptedChunk, nonce));
        }
        Optional<FileAccess> meta = context.getMetadata(getNext());
        return meta.flatMap(m -> {
            try {
                FileRetriever nextRet = m.retriever();
                return nextRet.getEncryptedChunk(bytesRemainingUntilStart - Chunk.MAX_SIZE, truncateTo - Chunk.MAX_SIZE, nextRet.getNonce(), dataKey, getNext(), context, monitor);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    public Optional<Location> getLocationAt(Location startLocation, long offset, UserContext context) throws IOException {
        if (offset < Chunk.MAX_SIZE)
            return Optional.of(startLocation);
        Location next = getNext();
        if (next == null)
            return Optional.empty();
        if (offset < 2*Chunk.MAX_SIZE)
            return Optional.of(next); // chunk at this location hasn't been written yet, only referenced by previous chunk
        Optional<FileAccess> meta = context.getMetadata(next);
        return meta.flatMap(m -> {
            try {
                FileRetriever nextRet = m.retriever();
                return nextRet.getLocationAt(next, offset - Chunk.MAX_SIZE, context);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    public Location getNext() {
        return this.nextChunk;
    }

    public byte[] getNonce() {
        return chunkNonce;
    }

    public Optional<LocatedChunk> getChunkInputStream(UserContext context, SymmetricKey dataKey, long startIndex, long truncateTo, Location ourLocation, Consumer<Long> monitor) throws IOException {
        Optional<LocatedEncryptedChunk> fullEncryptedChunk = getEncryptedChunk(startIndex, truncateTo, chunkNonce, dataKey, ourLocation, context, monitor);

        if (!fullEncryptedChunk.isPresent()) {
            Optional<Location> unwrittenChunkLocation = getLocationAt(ourLocation, startIndex, context);
            return unwrittenChunkLocation.map(l -> new LocatedChunk(l,
                    new Chunk(new byte[Math.min(Chunk.MAX_SIZE, (int) (truncateTo - startIndex))], dataKey, l.mapKey,
                            context.randomBytes(TweetNaCl.SECRETBOX_NONCE_BYTES))));
        }

        return fullEncryptedChunk.map(enc -> {
            try {
                byte[] original = enc.chunk.decrypt(dataKey, enc.nonce);
                return new LocatedChunk(enc.location, new Chunk(original, dataKey, enc.location.mapKey, context.randomBytes(TweetNaCl.SECRETBOX_NONCE_BYTES)));
            } catch (IllegalStateException e) {
                throw new IllegalStateException("Couldn't decrypt chunk at mapkey: "+new ByteArrayWrapper(enc.location.mapKey), e);
            }
        });
    }

    public void serialize(DataSink buf) {
        buf.writeByte((byte)1); // This class
        buf.writeArray(chunkNonce);
        buf.writeArray(chunkAuth);
        buf.writeArray(ArrayOps.concat(fragmentHashes.stream().map(h -> new ByteArrayWrapper(h.toBytes())).collect(Collectors.toList())));
        buf.writeByte(this.nextChunk != null ? (byte)1 : 0);
        if (this.nextChunk != null)
            buf.write(this.nextChunk.serialize());
        fragmenter.serialize(buf);
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
        Fragmenter fragmenter = Fragmenter.deserialize(buf);

        return new EncryptedChunkRetriever(chunkNonce, chunkAuth, hashes, nextChunk, fragmenter);
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
