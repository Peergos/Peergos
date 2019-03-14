package peergos.shared.user.fs;

import jsinterop.annotations.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.storage.ContentAddressedStorage;
import peergos.shared.util.*;

import java.io.*;
import java.time.*;
import java.util.*;

/** The FileProperties class represents metadata for a file or directory
 *
 *  In the case of a directory, the only properties present are the name, modification time and isHidden.
 *
 */
@JsType
public class FileProperties implements Cborable {
    public static final int MAX_FILE_NAME_SIZE = 255;
    public static final FileProperties EMPTY = new FileProperties("", true, "", 0, LocalDateTime.MIN, false, Optional.empty());

    public final String name;
    public final boolean isDirectory;
    public final String mimeType;
    @JsIgnore
    public final long size;
    public final LocalDateTime modified;
    public final boolean isHidden;
    public final Optional<byte[]> thumbnail;

    public FileProperties(String name, boolean isDirectory, String mimeType, int sizeHi, int sizeLo,
                          LocalDateTime modified, boolean isHidden, Optional<byte[]> thumbnail) {
        if (name.length() > MAX_FILE_NAME_SIZE)
            throw new IllegalStateException("File and directory names must be less than 256 characters.");
        this.name = name;
        this.isDirectory = isDirectory;
        this.mimeType = mimeType;
        this.size = (sizeLo & 0xFFFFFFFFL) | ((sizeHi | 0L) << 32);
        this.modified = modified;
        this.isHidden = isHidden;
        this.thumbnail = thumbnail;
    }

    @JsIgnore
    public FileProperties(String name, boolean isDirectory, String mimeType, long size,
                          LocalDateTime modified, boolean isHidden, Optional<byte[]> thumbnail) {
        this(name, isDirectory, mimeType, (int)(size >> 32), (int) size, modified, isHidden, thumbnail);
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

    @Override
    @SuppressWarnings("unusable-by-js")
    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("d", new CborObject.CborBoolean(isDirectory));
        state.put("n", new CborObject.CborString(name));
        state.put("m", new CborObject.CborString(mimeType));
        state.put("s", new CborObject.CborLong(size));
        state.put("t", new CborObject.CborLong(modified.toEpochSecond(ZoneOffset.UTC)));
        state.put("h", new CborObject.CborBoolean(isHidden));
        thumbnail.ifPresent(thumb -> state.put("i", new CborObject.CborByteArray(thumb)));
        return CborObject.CborMap.build(state);
    }

    @SuppressWarnings("unusable-by-js")
    public static FileProperties fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for FileProperties! " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        boolean isDirectory = m.getBoolean("d");
        String name = m.getString("n");
        String mimeType = m.getString("m");
        long size = m.getLong("s");
        long modifiedEpochMillis = m.getLong("t");
        boolean isHidden = m.getBoolean("h");
        Optional<byte[]> thumbnail = m.getOptionalByteArray("i");

        LocalDateTime modified = LocalDateTime.ofEpochSecond(modifiedEpochMillis, 0, ZoneOffset.UTC);
        return new FileProperties(name, isDirectory, mimeType, size, modified, isHidden, thumbnail);
    }

    @JsIgnore
    public FileProperties withSize(long newSize) {
        return new FileProperties(name, isDirectory, mimeType, newSize, modified, isHidden, thumbnail);
    }

    public FileProperties withModified(LocalDateTime modified) {
        return new FileProperties(name, isDirectory, mimeType, size, modified, isHidden, thumbnail);
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