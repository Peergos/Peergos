package peergos.shared.user.fs;

import jsinterop.annotations.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.user.TagsList;
import peergos.shared.user.TagsListItem;
import peergos.shared.util.*;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** The FileProperties class represents metadata for a file or directory
 *
 *  In the case of a directory, the only properties present are the name, modification time and isHidden.
 *
 */
@JsType
public class FileProperties implements Cborable {
    public static final int MAX_FILE_NAME_SIZE = 255;
    public static final int MAX_PATH_SIZE = 4096;
    public static final FileProperties EMPTY = new FileProperties("", true, false, "", 0, LocalDateTime.MIN, false, Optional.empty(), Optional.empty(), Optional.empty());

    public final String name;
    public final boolean isDirectory;
    public final boolean isLink;
    public final String mimeType;
    public final Optional<TagsList> tags;

    @JsIgnore
    public final long size;
    public final LocalDateTime modified;
    public final boolean isHidden;
    public final Optional<byte[]> thumbnail;
    public final Optional<byte[]> streamSecret;

    public FileProperties(String name,
                          boolean isDirectory,
                          boolean isLink,
                          String mimeType,
                          int sizeHi, int sizeLo,
                          LocalDateTime modified,
                          boolean isHidden,
                          Optional<byte[]> thumbnail,
                          Optional<byte[]> streamSecret,
                          Optional<TagsList> tags) {
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
        this.isHidden = isHidden;
        this.thumbnail = thumbnail;
        this.streamSecret = streamSecret;
        this.tags = tags;
    }

    @JsIgnore
    public FileProperties(String name,
                          boolean isDirectory,
                          boolean isLink,
                          String mimeType,
                          long size,
                          LocalDateTime modified,
                          boolean isHidden,
                          Optional<byte[]> thumbnail,
                          Optional<byte[]> streamSecret,
                          Optional<TagsList> tags) {
        this(name, isDirectory, isLink, mimeType, (int)(size >> 32), (int) size, modified, isHidden, thumbnail, streamSecret, tags);
    }

    /** Override this properties name with the link's name
     *
     * @param link
     * @return
     */
    public FileProperties withLink(FileProperties link) {
        return new FileProperties(link.name, isDirectory, false, mimeType, size, modified, isHidden, thumbnail, streamSecret, tags);
    }

    public static void ensureValidParsedPath(Path path) {
        ensureValidPath(path.toString());
    }

    @JsMethod
    public static void ensureValidPath(String path) {
        if (path.length() > MAX_PATH_SIZE)
            throw new IllegalArgumentException("Path too long! Paths must be smaller than " + MAX_PATH_SIZE);
    }

    public static CompletableFuture<byte[]> calculateMapKey(byte[] streamSecret, byte[] firstMapKey, long offset, Hasher h) {
        long iterations = offset / Chunk.MAX_SIZE;
        List<Long> counter = new ArrayList<>();
        for (long i=0; i < iterations; i++)
            counter.add(i);
        return Futures.reduceAll(counter, firstMapKey,
                (current, i) -> calculateNextMapKey(streamSecret, current, h), (a, b) -> b);
    }

    public static CompletableFuture<byte[]> calculateNextMapKey(byte[] streamSecret, byte[] currentMapKey, Hasher h) {
        return h.sha256(ArrayOps.concat(streamSecret, currentMapKey));
    }

    public int sizeLow() {
        return (int) size;
    }

    public int sizeHigh() {
        return (int) (size >> 32);
    }

    public int getNumberOfChunks() {
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
        state.put("h", new CborObject.CborBoolean(isHidden));
        thumbnail.ifPresent(thumb -> state.put("i", new CborObject.CborByteArray(thumb)));
        streamSecret.ifPresent(secret -> state.put("p", new CborObject.CborByteArray(secret)));
        tags.ifPresent((TagsList tags) -> state.put("r", tags));
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
        long modifiedEpochMillis = m.getLong("t");
        boolean isHidden = m.getBoolean("h");
        Optional<byte[]> thumbnail = m.getOptionalByteArray("i");
        Optional<byte[]> streamSecret = m.getOptionalByteArray("p");
        Optional<TagsList> tags = m.getOptional("r", TagsList::fromCbor);

        LocalDateTime modified = LocalDateTime.ofEpochSecond(modifiedEpochMillis, 0, ZoneOffset.UTC);
        return new FileProperties(name, isDirectory, isLink, mimeType, size, modified, isHidden, thumbnail, streamSecret, tags);
    }

    @JsIgnore
    public FileProperties withSize(long newSize) {
        return new FileProperties(name, isDirectory, isLink, mimeType, newSize, modified, isHidden, thumbnail, streamSecret, tags);
    }

    public FileProperties withNoThumbnail() {
        return new FileProperties(name, isDirectory, isLink, mimeType, size, modified, isHidden, Optional.empty(), streamSecret, tags);
    }
    public FileProperties withThumbnail(byte[] newThumbnail) {
        return new FileProperties(name, isDirectory, isLink, mimeType, size, modified, isHidden, Optional.of(newThumbnail), streamSecret, tags);
    }

    public FileProperties withModified(LocalDateTime modified) {
        return new FileProperties(name, isDirectory, isLink, mimeType, size, modified, isHidden, thumbnail, streamSecret, tags);
    }

    public FileProperties withNewStreamSecret(byte[] streamSecret) {
        return new FileProperties(name, isDirectory, isLink, mimeType, size, modified, isHidden, thumbnail, Optional.of(streamSecret), tags);
    }

    public FileProperties asLink() {
        return new FileProperties(name, isDirectory, true, mimeType, size, modified, isHidden, thumbnail, streamSecret, tags);
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
                ", isHidden=" + isHidden +
                ", thumbnail=" + thumbnail +
                '}';
    }
}