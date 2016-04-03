package peergos.user.fs;

import org.ipfs.api.*;
import peergos.crypto.*;
import peergos.crypto.symmetric.*;
import peergos.user.*;
import peergos.util.Serialize;

import java.io.*;
import java.time.*;
import java.util.*;
import java.util.function.*;

public class FileUploader implements AutoCloseable {

    private final String name;
    private final long offset, length;
    private final FileProperties props;
    private final SymmetricKey key;
    private final long nchunks;
    private final Location parentLocation;
    private final SymmetricKey parentparentKey;
    private final Consumer<Long> monitor;
    private final int nOriginalFragments, nAllowedFalures;
    private final InputStream raf; // resettable input stream
    public FileUploader(String name, InputStream fileData, long offset, long length, SymmetricKey key, Location parentLocation, SymmetricKey parentparentKey,
                        Consumer<Long> monitor, FileProperties fileProperties, int nOriginalFragments, int nAllowedFalures) throws IOException {
        if (fileProperties == null)
            this.props = new FileProperties(name, length, LocalDateTime.now(), false, Optional.empty());
        else
            this.props = fileProperties;
        if (key == null) key = SymmetricKey.random();

        // Process and upload chunk by chunk to avoid running out of RAM, in reverse order to build linked list
        this.nchunks = (long) Math.ceil((double) length / Chunk.MAX_SIZE);
        this.name = name;
        this.offset = offset;
        this.length = length;
        this.raf = fileData;
        this.key = key;
        this.parentLocation = parentLocation;
        this.parentparentKey = parentparentKey;
        this.monitor = monitor;
        this.nOriginalFragments = nOriginalFragments != -1 ? nOriginalFragments : EncryptedChunk.ERASURE_ORIGINAL;
        this.nAllowedFalures = nAllowedFalures != -1 ? nAllowedFalures : EncryptedChunk.ERASURE_ALLOWED_FAILURES;
    }

    public Location uploadChunk(UserContext context, UserPublicKey owner, User writer, long chunkIndex,
                                Location nextLocation, Consumer<Long> monitor) throws IOException {
	    System.out.println("uploading chunk: "+chunkIndex + " of "+name);

        long position = chunkIndex * Chunk.MAX_SIZE;
        raf.reset();
        raf.skip(position);

        long fileLength = length;
        boolean isLastChunk = fileLength < position + Chunk.MAX_SIZE;
        int length =  isLastChunk ? (int)(fileLength -  position) : Chunk.MAX_SIZE;
        byte[] data = new byte[length];
        Serialize.readFullArray(raf, data);

		Chunk chunk = new Chunk(data, key);
        LocatedChunk locatedChunk = new LocatedChunk(new Location(owner, writer, chunk.mapKey()), chunk);
        uploadChunk(writer, props, parentLocation, parentparentKey, locatedChunk, nOriginalFragments, nAllowedFalures, nextLocation, context, monitor);
        Location nextL = new Location(owner, writer, chunk.mapKey());
        if (chunkIndex > 0)
            return uploadChunk(context, owner, writer, chunkIndex-1, nextL, monitor);
        return nextL;
    }

    public Location upload(UserContext context, UserPublicKey owner, User writer) throws IOException {
        long t1 = System.currentTimeMillis();
        Location res = uploadChunk(context, owner, writer, this.nchunks-1, null, null);
        System.out.println("File encryption, erasure coding and upload took: " +(System.currentTimeMillis()-t1) + " mS");
        return res;
    }

    public static void uploadChunk(User writer, FileProperties props, Location parentLocation, SymmetricKey parentparentKey,
                                   LocatedChunk chunk, int nOriginalFragments, int nAllowedFalures, Location nextChunkLocation,
                                   UserContext context, Consumer<Long> monitor) throws IOException {
        EncryptedChunk encryptedChunk = chunk.chunk.encrypt();
        List<Fragment> fragments = encryptedChunk.generateFragments(nOriginalFragments, nAllowedFalures);
        System.out.printf("Uploading chunk with %d fragments\n", fragments.size());
        List<Multihash> hashes = context.uploadFragments(fragments, chunk.location.owner, chunk.location.writer, chunk.chunk.mapKey(), monitor);
        FileRetriever retriever = new EncryptedChunkRetriever(chunk.chunk.nonce(), encryptedChunk.getAuth(), hashes, nextChunkLocation, nOriginalFragments, nAllowedFalures);
        FileAccess metaBlob = FileAccess.create(chunk.chunk.key(), SymmetricKey.random(), props, retriever, parentLocation, parentparentKey);
        context.uploadChunk(metaBlob, chunk.location.owner, writer, chunk.chunk.mapKey(), hashes);
        Location nextL = new Location(chunk.location.owner, chunk.location.writer, chunk.chunk.mapKey());
    }

    public void close() throws IOException  {
        raf.close();
    }
}
