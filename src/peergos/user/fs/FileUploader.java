package peergos.user.fs;

import org.ipfs.api.*;
import peergos.crypto.*;
import peergos.crypto.symmetric.*;
import peergos.user.*;

import java.io.*;
import java.time.*;
import java.util.*;
import java.util.function.*;

public class FileUploader {

    private final File file;
    private final FileProperties props;
    private final SymmetricKey key;
    private final long nchunks;
    private final Location parentLocation;
    private final SymmetricKey parentparentKey;
    private final Consumer<Long> monitor;
    private final int nOriginalFragments, nAllowedFalures;

    public FileUploader(String name, File file, SymmetricKey key, Location parentLocation, SymmetricKey parentparentKey,
                        Consumer<Long> monitor, FileProperties fileProperties, int nOriginalFragments, int nAllowedFalures) {
        if (fileProperties == null)
            this.props = new FileProperties(name, file.length(), LocalDateTime.now(), false, Optional.empty());
        else
            this.props = fileProperties;
        if (key == null) key = SymmetricKey.random();

        // Process and upload chunk by chunk to avoid running out of RAM, in reverse order to build linked list
        this.nchunks = (long) Math.ceil((double) file.length() / Chunk.MAX_SIZE);

        this.file = file;
        this.key = key;
        this.parentLocation = parentLocation;
        this.parentparentKey = parentparentKey;
        this.monitor = monitor;
        this.nOriginalFragments = nOriginalFragments != -1 ? nOriginalFragments : EncryptedChunk.ERASURE_ORIGINAL;
        this.nAllowedFalures = nAllowedFalures != -1 ? nAllowedFalures : EncryptedChunk.ERASURE_ALLOWED_FAILURES;
    }

    public Location uploadChunk(UserContext context, UserPublicKey owner, User writer, long chunkIndex, File file,
                                Location nextLocation, Consumer<Long> monitor) throws IOException {
	    System.out.println("uploading chunk: "+chunkIndex + " of "+file.getName());
        RandomAccessFile raf = new RandomAccessFile(file, "r");
        long position = chunkIndex * Chunk.MAX_SIZE;
        raf.seek(position);

        long fileLength = file.length();
        boolean isLastChunk = fileLength < position + Chunk.MAX_SIZE;
        long length =  isLastChunk ? (fileLength -  position) : Chunk.MAX_SIZE;
        byte[] data = new byte[(int) length];

        raf.readFully(data);
		Chunk chunk = new Chunk(data, key);
		EncryptedChunk encryptedChunk = chunk.encrypt();
		List<Fragment> fragments = encryptedChunk.generateFragments(nOriginalFragments, nAllowedFalures);
        System.out.printf("Uploading chunk with %d fragments\n", fragments.size());
        List<Multihash> hashes = context.uploadFragments(fragments, owner, writer, chunk.mapKey(), monitor);
        FileRetriever retriever = new EncryptedChunkRetriever(chunk.nonce(), encryptedChunk.getAuth(), hashes, nextLocation, nOriginalFragments, nAllowedFalures);
        FileAccess metaBlob = FileAccess.create(chunk.key(), props, retriever, parentLocation, parentparentKey);
        context.uploadChunk(metaBlob, owner, writer, chunk.mapKey(), hashes);
        Location nextL = new Location(owner, writer, chunk.mapKey());
        if (chunkIndex > 0)
            return uploadChunk(context, owner, writer, chunkIndex-1, file, nextL, monitor);
        return nextL;
    }

    public Location upload(UserContext context, UserPublicKey owner, User writer) throws IOException {
        long t1 = System.currentTimeMillis();
        Location res = uploadChunk(context, owner, writer, this.nchunks-1, this.file, null, null);
        System.out.println("File encryption, erasure coding and upload took: " +(System.currentTimeMillis()-t1) + " mS");
        return res;
    }
}
