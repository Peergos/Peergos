package peergos.shared.user.fs;

import peergos.shared.crypto.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.ipfs.api.Multihash;
import peergos.shared.user.*;
import peergos.shared.util.Serialize;
import peergos.shared.util.StringUtils;

import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

public class FileUploader implements AutoCloseable {

    private final String name;
    private final long offset, length;
    private final FileProperties props;
    private final SymmetricKey baseKey, metaKey;
    private final long nchunks;
    private final Location parentLocation;
    private final SymmetricKey parentparentKey;
    private final Consumer<Long> monitor;
    private final int nOriginalFragments, nAllowedFalures;
    private final peergos.shared.user.fs.Fragmenter fragmenter;
    private final LazyArrayReader raf; // resettable input stream
    public FileUploader(String name, LazyArrayReader fileData, long offset, long length, SymmetricKey baseKey, SymmetricKey metaKey, Location parentLocation, SymmetricKey parentparentKey,
                        Consumer<Long> monitor, FileProperties fileProperties, int nOriginalFragments, int nAllowedFalures) throws IOException {
//        if (! fileData.markSupported())
//            throw new IllegalStateException("InputStream needs to be resettable!");
        if (fileProperties == null)
            this.props = new FileProperties(name, length, LocalDateTime.now(), false, Optional.empty());
        else
            this.props = fileProperties;
        if (baseKey == null) baseKey = SymmetricKey.random();

        fragmenter = nAllowedFalures == 0 ?
                new peergos.shared.user.fs.SplitFragmenter() : new peergos.shared.user.fs.ErasureFragmenter(nOriginalFragments, nAllowedFalures);


        // Process and upload chunk by chunk to avoid running out of RAM, in reverse order to build linked list
        this.nchunks = length > 0 ? (length + Chunk.MAX_SIZE - 1) / Chunk.MAX_SIZE : 1;
        this.name = name;
        this.offset = offset;
        this.length = length;
        this.raf = fileData;
        this.baseKey = baseKey;
        this.metaKey = metaKey;
        this.parentLocation = parentLocation;
        this.parentparentKey = parentparentKey;
        this.monitor = monitor;
        this.nOriginalFragments = nOriginalFragments != -1 ? nOriginalFragments : EncryptedChunk.ERASURE_ORIGINAL;
        this.nAllowedFalures = nAllowedFalures != -1 ? nAllowedFalures : EncryptedChunk.ERASURE_ALLOWED_FAILURES;
    }

    public Location uploadChunk(UserContext context, UserPublicKey owner, User writer, long chunkIndex,
                                Location currentLocation, Consumer<Long> monitor) throws IOException {
	    System.out.println("uploading chunk: "+chunkIndex + " of "+name);

        long position = chunkIndex * Chunk.MAX_SIZE;

        long fileLength = length;
        boolean isLastChunk = fileLength < position + Chunk.MAX_SIZE;
        int length =  isLastChunk ? (int)(fileLength -  position) : Chunk.MAX_SIZE;
        byte[] data = new byte[length];
        Serialize.readFullArray(raf, data);

        byte[] nonce = context.randomBytes(TweetNaCl.SECRETBOX_NONCE_BYTES);
        Chunk chunk = new Chunk(data, metaKey, currentLocation.getMapKey(), nonce);
        LocatedChunk locatedChunk = new LocatedChunk(new Location(owner, writer, chunk.mapKey()), chunk);
        byte[] mapKey = context.randomBytes(32);
        Location nextLocation = new Location(owner, writer, mapKey);
        uploadChunk(writer, props, parentLocation, parentparentKey, baseKey, locatedChunk, nOriginalFragments, nAllowedFalures, nextLocation, context, monitor);
        return nextLocation;
    }

    public Location upload(UserContext context, UserPublicKey owner, User writer, Location currentChunk) throws IOException {
        long t1 = System.currentTimeMillis();
        Location originalChunk = currentChunk;

        for (int i=0; i < nchunks; i++)
            currentChunk = uploadChunk(context, owner, writer, i, currentChunk, l -> {});
        System.out.println("File encryption, erasure coding and upload took: " +(System.currentTimeMillis()-t1) + " mS");
        return originalChunk;
    }

    public static CompletableFuture<Boolean> uploadChunk(User writer, FileProperties props, Location parentLocation, SymmetricKey parentparentKey,
                        SymmetricKey baseKey, LocatedChunk chunk, int nOriginalFragments, int nAllowedFalures, Location nextChunkLocation,
                        UserContext context, Consumer<Long> monitor) {
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
        raf.close();
    }
}
