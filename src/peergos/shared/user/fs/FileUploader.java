package peergos.shared.user.fs;

import jsinterop.annotations.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

public class FileUploader implements AutoCloseable {

    private final String name;
    private final long offset, length;
    private final FileProperties props;
    private final SymmetricKey baseKey, metaKey;
    private final long nchunks;
    private final Location parentLocation;
    private final SymmetricKey parentparentKey;
    private final ProgressConsumer<Long> monitor;
    private final int nOriginalFragments, nAllowedFalures;
    private final peergos.shared.user.fs.Fragmenter fragmenter;
    private final AsyncReader reader; // resettable input stream

    @JsConstructor
    public FileUploader(String name, AsyncReader fileData, int offsetHi, int offsetLow, int lengthHi, int lengthLow,
                        SymmetricKey baseKey, SymmetricKey metaKey, Location parentLocation, SymmetricKey parentparentKey,
                        ProgressConsumer<Long> monitor, FileProperties fileProperties, int nOriginalFragments, int nAllowedFalures) {
        long length = lengthLow + ((lengthHi & 0xFFFFFFFFL) << 32);
        if (fileProperties == null)
            this.props = new FileProperties(name, length, LocalDateTime.now(), false, Optional.empty());
        else
            this.props = fileProperties;
        if (baseKey == null) baseKey = SymmetricKey.random();

        fragmenter = nAllowedFalures == 0 ?
                new peergos.shared.user.fs.SplitFragmenter() : new peergos.shared.user.fs.ErasureFragmenter(nOriginalFragments, nAllowedFalures);

        long offset = offsetLow + ((offsetHi & 0xFFFFFFFFL) << 32);

        // Process and upload chunk by chunk to avoid running out of RAM, in reverse order to build linked list
        this.nchunks = length > 0 ? (length + Chunk.MAX_SIZE - 1) / Chunk.MAX_SIZE : 1;
        this.name = name;
        this.offset = offset;
        this.length = length;
        this.reader = fileData;
        this.baseKey = baseKey;
        this.metaKey = metaKey;
        this.parentLocation = parentLocation;
        this.parentparentKey = parentparentKey;
        this.monitor = monitor;
        this.nOriginalFragments = nOriginalFragments != -1 ? nOriginalFragments : EncryptedChunk.ERASURE_ORIGINAL;
        this.nAllowedFalures = nAllowedFalures != -1 ? nAllowedFalures : EncryptedChunk.ERASURE_ALLOWED_FAILURES;
    }

    public FileUploader(String name, AsyncReader fileData, long offset, long length, SymmetricKey baseKey, SymmetricKey metaKey, Location parentLocation, SymmetricKey parentparentKey,
                        ProgressConsumer<Long> monitor, FileProperties fileProperties, int nOriginalFragments, int nAllowedFalures) {
        this(name, fileData, (int)(offset >> 32), (int) offset, (int) (length >> 32), (int) length,
                baseKey, metaKey, parentLocation, parentparentKey, monitor, fileProperties, nOriginalFragments, nAllowedFalures);
    }

    public CompletableFuture<Location> uploadChunk(UserContext context, UserPublicKey owner, SigningKeyPair writer, long chunkIndex,
                                                   Location currentLocation, ProgressConsumer<Long> monitor) {
	    System.out.println("uploading chunk: "+chunkIndex + " of "+name);

        long position = chunkIndex * Chunk.MAX_SIZE;

        long fileLength = length;
        boolean isLastChunk = fileLength < position + Chunk.MAX_SIZE;
        int length =  isLastChunk ? (int)(fileLength -  position) : Chunk.MAX_SIZE;
        byte[] data = new byte[length];
        return reader.readIntoArray(data, 0, data.length).thenCompose(b -> {
            byte[] nonce = context.randomBytes(TweetNaCl.SECRETBOX_NONCE_BYTES);
            Chunk chunk = new Chunk(data, metaKey, currentLocation.getMapKey(), nonce);
            LocatedChunk locatedChunk = new LocatedChunk(new Location(owner, writer, chunk.mapKey()), chunk);
            byte[] mapKey = context.randomBytes(32);
            Location nextLocation = new Location(owner, writer, mapKey);
            return uploadChunk(writer, props, parentLocation, parentparentKey, baseKey, locatedChunk,
                    nOriginalFragments, nAllowedFalures, nextLocation, context, monitor).thenApply(c -> nextLocation);
        });
    }

    public CompletableFuture<Location> upload(UserContext context, UserPublicKey owner, SigningKeyPair writer, Location currentChunk) {
        long t1 = System.currentTimeMillis();
        Location originalChunk = currentChunk;

        List<Integer> input = IntStream.range(0, (int) nchunks).mapToObj(i -> Integer.valueOf(i)).collect(Collectors.toList());
        return Futures.reduceAll(input, currentChunk, (loc, i) -> uploadChunk(context, owner, writer, i, loc, monitor), (a, b) -> b)
                .thenApply(loc -> {
                    System.out.println("File encryption, erasure coding and upload took: " +(System.currentTimeMillis()-t1) + " mS");
                    return originalChunk;
                });
    }

    public static CompletableFuture<Boolean> uploadChunk(SigningKeyPair writer, FileProperties props, Location parentLocation, SymmetricKey parentparentKey,
                                                         SymmetricKey baseKey, LocatedChunk chunk, int nOriginalFragments, int nAllowedFalures, Location nextChunkLocation,
                                                         UserContext context, ProgressConsumer<Long> monitor) {
        EncryptedChunk encryptedChunk = chunk.chunk.encrypt();

        peergos.shared.user.fs.Fragmenter fragmenter = nAllowedFalures == 0 ?
                new peergos.shared.user.fs.SplitFragmenter() :
                new peergos.shared.user.fs.ErasureFragmenter(nOriginalFragments, nAllowedFalures);

        List<Fragment> fragments = encryptedChunk.generateFragments(fragmenter);
        System.out.println(StringUtils.format("Uploading chunk with %d fragments\n", fragments.size()));
        return context.uploadFragments(fragments, chunk.location.owner, chunk.location.writer, chunk.chunk.mapKey(), monitor).thenCompose(hashes -> {
            FileRetriever retriever = new EncryptedChunkRetriever(chunk.chunk.nonce(), encryptedChunk.getAuth(), hashes, nextChunkLocation, fragmenter);
            FileAccess metaBlob = FileAccess.create(baseKey, chunk.chunk.key(), props, retriever, parentLocation, parentparentKey);
            return context.uploadChunk(metaBlob, new Location(chunk.location.owner, writer, chunk.chunk.mapKey()), hashes);
        });
    }

    public void close() throws IOException  {
        reader.close();
    }
}
