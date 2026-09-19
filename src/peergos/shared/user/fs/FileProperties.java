package peergos.shared.user.fs;

import jsinterop.annotations.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.storage.auth.*;
import peergos.shared.util.*;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

/** The FileProperties class represents metadata for a file or directory
 *
 *  In the case of a directory, the only properties present are the name, modification time and isHidden.
 *
 */
@JsType
public class FileProperties implements Cborable {
    public static final int MAX_FILE_NAME_SIZE = 255;
    public static final int MAX_PATH_SIZE = 4096;
    public static final FileProperties EMPTY = new FileProperties(".subsequent-dir-chunk", true, false, "", 0,
            LocalDateTime.MIN, LocalDateTime.MIN, false, Optional.empty(), Optional.empty(), Optional.empty());

    public final String name;
    public final boolean isDirectory;
    public final boolean isLink;
    public final String mimeType;
    @JsIgnore
    public final long size;
    public final LocalDateTime modified;
    public final LocalDateTime created;
    public final boolean isHidden;
    public final Optional<Thumbnail> thumbnail;
    public final Optional<byte[]> streamSecret;
    public final Optional<HashBranch> treeHash;
    /**
     * The size of this file's chunks. Data rather than a constant because it cannot change for a
     * file once written: every chunk's label comes from walking offset/chunkSize steps of a hash
     * chain, so reading a file at the wrong chunk size derives the right labels and assembles
     * them at the wrong spacing - silently wrong bytes rather than an error.
     */
    public final int chunkSize;

    public FileProperties(String name,
                          boolean isDirectory,
                          boolean isLink,
                          String mimeType,
                          int sizeHi, int sizeLo,
                          LocalDateTime modified,
                          LocalDateTime created,
                          boolean isHidden,
                          Optional<Thumbnail> thumbnail,
                          Optional<byte[]> streamSecret,
                          Optional<HashBranch> treeHash,
                          int chunkSize) {
        if (name.length() > MAX_FILE_NAME_SIZE)
            throw new IllegalStateException("File and directory names must be less than 256 characters.");
        if (isDirectory && streamSecret.isPresent())
            throw new IllegalStateException("Directories cannot have stream secrets!");
        if (name.contains("/"))
            throw new IllegalStateException("Invalid character in filename!");
        if (name.equals(".") || name.equals("..") || name.isEmpty())
            throw new IllegalStateException("Invalid filename: " + name);
        this.name = name;
        this.isDirectory = isDirectory;
        this.isLink = isLink;
        this.mimeType = mimeType;
        this.size = (sizeLo & 0xFFFFFFFFL) | ((sizeHi | 0L) << 32);
        this.modified = modified;
        this.created = created;
        this.isHidden = isHidden;
        this.thumbnail = thumbnail;
        this.streamSecret = streamSecret;
        this.treeHash = treeHash;
        this.chunkSize = chunkSize;
    }

    /** Legacy chunk size, for the callers that predate the chunk size being data. */
    @JsIgnore
    public FileProperties(String name,
                          boolean isDirectory,
                          boolean isLink,
                          String mimeType,
                          int sizeHi, int sizeLo,
                          LocalDateTime modified,
                          LocalDateTime created,
                          boolean isHidden,
                          Optional<Thumbnail> thumbnail,
                          Optional<byte[]> streamSecret,
                          Optional<HashBranch> treeHash) {
        this(name, isDirectory, isLink, mimeType, sizeHi, sizeLo, modified, created, isHidden, thumbnail,
                streamSecret, treeHash, Chunk.LEGACY_SIZE);
    }

    @JsIgnore
    public FileProperties(String name,
                          boolean isDirectory,
                          boolean isLink,
                          String mimeType,
                          long size,
                          LocalDateTime modified,
                          LocalDateTime created,
                          boolean isHidden,
                          Optional<Thumbnail> thumbnail,
                          Optional<byte[]> streamSecret,
                          Optional<HashBranch> treeHash) {
        this(name, isDirectory, isLink, mimeType, (int)(size >> 32), (int) size, modified, created, isHidden, thumbnail,
                streamSecret, treeHash, Chunk.LEGACY_SIZE);
    }

    @JsIgnore
    public FileProperties(String name,
                          boolean isDirectory,
                          boolean isLink,
                          String mimeType,
                          long size,
                          LocalDateTime modified,
                          LocalDateTime created,
                          boolean isHidden,
                          Optional<Thumbnail> thumbnail,
                          Optional<byte[]> streamSecret,
                          Optional<HashBranch> treeHash,
                          int chunkSize) {
        this(name, isDirectory, isLink, mimeType, (int)(size >> 32), (int) size, modified, created, isHidden, thumbnail,
                streamSecret, treeHash, chunkSize);
    }

    public FileProperties withChunkSize(int chunkSize) {
        return new FileProperties(name, isDirectory, isLink, mimeType, size, modified, created, isHidden,
                thumbnail, streamSecret, treeHash, chunkSize);
    }

    /** Override this properties name with the link's name
     *
     * @param link
     * @return
     */
    public FileProperties withLink(FileProperties link) {
        return new FileProperties(link.name, isDirectory, false, mimeType, size, modified, created, isHidden, thumbnail, streamSecret, treeHash, chunkSize);
    }

    public static void ensureValidParsedPath(Path path) {
        ensureValidPath(path.toString());
    }

    @JsMethod
    public static void ensureValidPath(String path) {
        if (path.length() > MAX_PATH_SIZE)
            throw new IllegalArgumentException("Path too long! Paths must be smaller than " + MAX_PATH_SIZE);
    }

    /**
     * The label of the chunk containing {@code offset}, found by walking the hash chain.
     *
     * The chunk size is a parameter rather than a constant because it belongs to the file: using
     * the wrong one derives labels that exist, at the wrong spacing, so the read succeeds and
     * returns the wrong bytes.
     */
    public static CompletableFuture<Pair<byte[], Optional<Bat>>> calculateMapKey(byte[] streamSecret,
                                                                                 byte[] firstMapKey,
                                                                                 Optional<Bat> firstBat,
                                                                                 long offset,
                                                                                 int chunkSize,
                                                                                 Hasher h) {
        long iterations = offset / chunkSize;
        List<Long> counter = new ArrayList<>();
        for (long i=0; i < iterations; i++)
            counter.add(i);
        return Futures.reduceAll(counter, new Pair<>(firstMapKey, firstBat),
                (current, i) -> calculateNextMapKey(streamSecret, current.left, current.right, h), (a, b) -> b);
    }

    private static <V> List<V> list(V elem) {
        List<V> res = new ArrayList<>();
        res.add(elem);
        return res;
    }

    private static <V> List<V> add(List<V> start, V elem) { // needed for gwt
        start.add(elem);
        return start;
    }
    public static CompletableFuture<List<Pair<byte[], Optional<Bat>>>> calculateSubsequentMapKeys(byte[] streamSecret,
                                                                                                  byte[] firstMapKey,
                                                                                                  Optional<Bat> firstBat,
                                                                                                  int nChunks,
                                                                                                  Hasher h) {
        List<Long> counter = new ArrayList<>();
        for (long i=0; i < nChunks; i++)
            counter.add(i);
        List<Pair<byte[], Optional<Bat>>> first = list(new Pair<>(firstMapKey, firstBat));
        return Futures.reduceAll(counter, first,
                (current, i) -> calculateNextMapKey(streamSecret,
                        current.get(current.size() - 1).left,
                        current.get(current.size() - 1).right, h)
                        .thenApply(next -> add(current, next)),
        (a, b) -> Stream.concat(a.stream(), b.stream()).collect(Collectors.toList()));
    }

    public static CompletableFuture<Pair<byte[], Optional<Bat>>> calculateNextMapKey(byte[] streamSecret,
                                                                                     byte[] currentMapKey,
                                                                                     Optional<Bat> currentBat,
                                                                                     Hasher h) {
        return h.sha256(ArrayOps.concat(streamSecret, currentMapKey))
                .thenCompose(nextMapKey -> (currentBat.isPresent() ?
                        h.sha256(ArrayOps.concat(streamSecret, currentBat.get().secret))
                                .thenApply(Bat::new).thenApply(Optional::of) :
                        Futures.of(Optional.<Bat>empty()))
                        .thenApply(nextBat -> new Pair<>(nextMapKey, nextBat)));
    }

    public int sizeLow() {
        return (int) size;
    }

    public int sizeHigh() {
        return (int) (size >> 32);
    }

    public int chunkCount() {
        return FileWrapper.getNumberOfChunks(size, chunkSize);
    }

    @JsMethod
    public boolean isSocialPost() {
        return MimeTypes.PEERGOS_POST.equals(mimeType);
    }

    @Override
    @SuppressWarnings("unusable-by-js")
    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("d", new CborObject.CborBoolean(isDirectory));
        state.put("l", new CborObject.CborBoolean(isLink));
        state.put("n", new CborObject.CborString(name));
        state.put("m", new CborObject.CborString(mimeType));
        state.put("s", new CborObject.CborLong(size));
        state.put("t", new CborObject.CborLong(modified.toEpochSecond(ZoneOffset.UTC)));
        state.put("tn", new CborObject.CborLong(modified.getNano()));
        state.put("c", new CborObject.CborLong(created.toEpochSecond(ZoneOffset.UTC)));
        state.put("cn", new CborObject.CborLong(created.getNano()));
        state.put("h", new CborObject.CborBoolean(isHidden));
        treeHash.ifPresent(b -> state.put("th", b.toCbor()));
        thumbnail.ifPresent(thumb -> state.put("i", new CborObject.CborByteArray(thumb.data)));
        thumbnail.ifPresent(thumb -> state.put("im", new CborObject.CborString(thumb.mimeType)));
        streamSecret.ifPresent(secret -> state.put("p", new CborObject.CborByteArray(secret)));
        // absent means the legacy size, so the cbor of every existing file is unchanged
        if (chunkSize != Chunk.LEGACY_SIZE)
            state.put("cs", new CborObject.CborLong(chunkSizeLog2(chunkSize)));
        return CborObject.CborMap.build(state);
    }

    @SuppressWarnings("unusable-by-js")
    public static FileProperties fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for FileProperties! " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        boolean isDirectory = m.getBoolean("d");
        boolean isLink = m.getBoolean("l", false);
        String name = m.getString("n");
        String mimeType = m.getString("m");
        long size = m.getLong("s");
        long modifiedEpochSeconds = m.getLong("t");
        int modifiedNano = m.getOptionalLong("tn").orElse(0L).intValue();
        long createdEpochSeconds = m.getOptionalLong("c").orElse(modifiedEpochSeconds);
        int createdNano = m.getOptionalLong("cn").orElse(0L).intValue();
        boolean isHidden = m.getBoolean("h");
        Optional<byte[]> thumbnailData = m.getOptionalByteArray("i");
        Optional<Thumbnail> thumbnail = thumbnailData.map(d -> new Thumbnail(m.getString("im", "image/png"), d));
        Optional<byte[]> streamSecret = m.getOptionalByteArray("p");
        Optional<HashBranch> th = m.getOptional("th", HashBranch::fromCbor);
        int chunkSize = m.getOptionalLong("cs").map(FileProperties::chunkSizeFromLog2).orElse(Chunk.LEGACY_SIZE);

        LocalDateTime modified = LocalDateTime.ofEpochSecond(modifiedEpochSeconds, modifiedNano, ZoneOffset.UTC);
        LocalDateTime created = LocalDateTime.ofEpochSecond(createdEpochSeconds, createdNano, ZoneOffset.UTC);
        return new FileProperties(name, isDirectory, isLink, mimeType, size, modified, created, isHidden, thumbnail,
                streamSecret, th, chunkSize);
    }

    /**
     * The chunk size is stored as its log2, so any size that can ever be stored is a power of
     * two - which is what BLAKE3 subtree alignment requires anyway.
     *
     * Only the sizes actually in use are accepted: a file claiming some other size is a bad file
     * rather than a new code path to support. The encoding leaves room to allow more later.
     */
    /**
     * The chunk size a file created right now gets. The single place that decides, so that a
     * write path can never pick a size for a file that already exists: everything else takes the
     * size from the file's own properties.
     */
    @JsMethod
    public static int chunkSizeForNewFiles() {
        return Chunk.LEGACY_SIZE;
    }

    public static int chunkSizeFromLog2(long log2) {
        int size = 1 << log2;
        if (log2 < 0 || log2 > 30 || (size != Chunk.DEFAULT_SIZE && size != Chunk.LEGACY_SIZE))
            throw new IllegalStateException("Unsupported chunk size in file properties: 2^" + log2);
        return size;
    }

    public static long chunkSizeLog2(int size) {
        return Integer.numberOfTrailingZeros(size);
    }

    @JsIgnore
    public FileProperties withSize(long newSize) {
        return new FileProperties(name, isDirectory, isLink, mimeType, newSize, modified, created, isHidden, thumbnail, streamSecret, Optional.empty(), chunkSize);
    }

    public FileProperties withHash(Optional<HashBranch> treeHash) {
        return new FileProperties(name, isDirectory, isLink, mimeType, size, modified, created, isHidden, thumbnail, streamSecret, treeHash, chunkSize);
    }

    public FileProperties withNoThumbnail() {
        return new FileProperties(name, isDirectory, isLink, mimeType, size, modified, created, isHidden, Optional.empty(), streamSecret, treeHash, chunkSize);
    }
    public FileProperties withThumbnail(Optional<Thumbnail> newThumbnail) {
        return new FileProperties(name, isDirectory, isLink, mimeType, size, modified, created, isHidden, newThumbnail, streamSecret, treeHash, chunkSize);
    }

    public FileProperties withModified(LocalDateTime modified) {
        return new FileProperties(name, isDirectory, isLink, mimeType, size, modified, created, isHidden, thumbnail, streamSecret, treeHash, chunkSize);
    }

    public FileProperties withNewStreamSecret(byte[] streamSecret) {
        return new FileProperties(name, isDirectory, isLink, mimeType, size, modified, created, isHidden, thumbnail, Optional.of(streamSecret), treeHash, chunkSize);
    }

    public FileProperties asLink() {
        return new FileProperties(name, isDirectory, true, mimeType, size, modified, created, isHidden, thumbnail, streamSecret, treeHash, chunkSize);
    }

    public String getType() {
        return getType(mimeType, isDirectory);
    }

    @JsMethod
    public static String getType(String mimeType, boolean isDirectory) {
        if (isDirectory)
            return "dir";
        if (mimeType.equals("text/calendar"))
            return "calendar";
        if (mimeType.equals("text/vcard"))
            return "contact file";
        if (mimeType.startsWith("image"))
            return "image";
        if (mimeType.startsWith("audio"))
            return "audio";
        if (mimeType.startsWith("video"))
            return "video";
        if (mimeType.startsWith("text"))
            return "text";
        if (mimeType.equals("application/pdf"))
            return "pdf";
        if (mimeType.equals("application/zip"))
            return "zip";
        if (mimeType.equals("application/json"))
            return "text";
        if (mimeType.equals("application/java-archive"))
            return "java-archive";

        if (mimeType.equals("application/vnd.openxmlformats-officedocument.presentationml.presentation"))
            return "powerpoint presentation";
        if (mimeType.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
            return "word document";
        if (mimeType.equals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            return "excel spreadsheet";

        if (mimeType.equals("application/vnd.oasis.opendocument.text"))
            return "text document";
        if (mimeType.equals("application/vnd.oasis.opendocument.spreadsheet"))
            return "spreadsheet";
        if (mimeType.equals("application/vnd.oasis.opendocument.presentation"))
            return "presentation";
        return "file";
    }

    @Override
    public String toString() {
        return "FileProperties{" +
                "name='" + name + '\'' +
                ", size=" + size +
                ", modified=" + modified +
                ", created=" + modified +
                ", isHidden=" + isHidden +
                ", thumbnail=" + thumbnail +
                '}';
    }
}