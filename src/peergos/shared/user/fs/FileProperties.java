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
    public static final FileProperties EMPTY = new FileProperties("", true, false, "", 0, LocalDateTime.MIN, LocalDateTime.MIN, false, Optional.empty(), Optional.empty());

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

    public FileProperties(String name,
                          boolean isDirectory,
                          boolean isLink,
                          String mimeType,
                          int sizeHi, int sizeLo,
                          LocalDateTime modified,
                          LocalDateTime created,
                          boolean isHidden,
                          Optional<Thumbnail> thumbnail,
                          Optional<byte[]> streamSecret) {
        if (name.length() > MAX_FILE_NAME_SIZE)
            throw new IllegalStateException("File and directory names must be less than 256 characters.");
        if (isDirectory && streamSecret.isPresent())
            throw new IllegalStateException("Directories cannot have stream secrets!");
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
                          Optional<byte[]> streamSecret) {
        this(name, isDirectory, isLink, mimeType, (int)(size >> 32), (int) size, modified, created, isHidden, thumbnail, streamSecret);
    }

    /** Override this properties name with the link's name
     *
     * @param link
     * @return
     */
    public FileProperties withLink(FileProperties link) {
        return new FileProperties(link.name, isDirectory, false, mimeType, size, modified, created, isHidden, thumbnail, streamSecret);
    }

    public static void ensureValidParsedPath(Path path) {
        ensureValidPath(path.toString());
    }

    @JsMethod
    public static void ensureValidPath(String path) {
        if (path.length() > MAX_PATH_SIZE)
            throw new IllegalArgumentException("Path too long! Paths must be smaller than " + MAX_PATH_SIZE);
    }

    public static CompletableFuture<Pair<byte[], Optional<Bat>>> calculateMapKey(byte[] streamSecret,
                                                                                 byte[] firstMapKey,
                                                                                 Optional<Bat> firstBat,
                                                                                 long offset,
                                                                                 Hasher h) {
        long iterations = offset / Chunk.MAX_SIZE;
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
        return FileWrapper.getNumberOfChunks(size);
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
        thumbnail.ifPresent(thumb -> state.put("i", new CborObject.CborByteArray(thumb.data)));
        thumbnail.ifPresent(thumb -> state.put("im", new CborObject.CborString(thumb.mimeType)));
        streamSecret.ifPresent(secret -> state.put("p", new CborObject.CborByteArray(secret)));
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

        LocalDateTime modified = LocalDateTime.ofEpochSecond(modifiedEpochSeconds, modifiedNano, ZoneOffset.UTC);
        LocalDateTime created = LocalDateTime.ofEpochSecond(createdEpochSeconds, createdNano, ZoneOffset.UTC);
        return new FileProperties(name, isDirectory, isLink, mimeType, size, modified, created, isHidden, thumbnail, streamSecret);
    }

    @JsIgnore
    public FileProperties withSize(long newSize) {
        return new FileProperties(name, isDirectory, isLink, mimeType, newSize, modified, created, isHidden, thumbnail, streamSecret);
    }

    public FileProperties withNoThumbnail() {
        return new FileProperties(name, isDirectory, isLink, mimeType, size, modified, created, isHidden, Optional.empty(), streamSecret);
    }
    public FileProperties withThumbnail(Optional<Thumbnail> newThumbnail) {
        return new FileProperties(name, isDirectory, isLink, mimeType, size, modified, created, isHidden, newThumbnail, streamSecret);
    }

    public FileProperties withModified(LocalDateTime modified) {
        return new FileProperties(name, isDirectory, isLink, mimeType, size, modified, created, isHidden, thumbnail, streamSecret);
    }

    public FileProperties withNewStreamSecret(byte[] streamSecret) {
        return new FileProperties(name, isDirectory, isLink, mimeType, size, modified, created, isHidden, thumbnail, Optional.of(streamSecret));
    }

    public FileProperties asLink() {
        return new FileProperties(name, isDirectory, true, mimeType, size, modified, created, isHidden, thumbnail, streamSecret);
    }

    public String getType() {
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
        if (mimeType.equals("application/vnd.peergos-todo"))
            return "todo";
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