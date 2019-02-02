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
    public static final FileProperties EMPTY = new FileProperties("", "", 0, LocalDateTime.MIN, false, Optional.empty());

    public final String name;
    public final String mimeType;
    @JsIgnore
    public final long size;
    public final LocalDateTime modified;
    public final boolean isHidden;
    public final Optional<byte[]> thumbnail;

    public FileProperties(String name, String mimeType, int sizeHi, int sizeLo,
                          LocalDateTime modified, boolean isHidden, Optional<byte[]> thumbnail) {
        if (name.length() > MAX_FILE_NAME_SIZE)
            throw new IllegalStateException("File and directory names must be less than 256 characters.");
        this.name = name;
        this.mimeType = mimeType;
        this.size = sizeLo | ((sizeHi | 0L) << 32);
        this.modified = modified;
        this.isHidden = isHidden;
        this.thumbnail = thumbnail;
    }

    @JsIgnore
    public FileProperties(String name, String mimeType, long size,
                          LocalDateTime modified, boolean isHidden, Optional<byte[]> thumbnail) {
        this(name, mimeType, (int)(size >> 32), (int) size, modified, isHidden, thumbnail);
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
        return new CborObject.CborList(Arrays.asList(
                new CborObject.CborString(name),
                new CborObject.CborString(mimeType),
                new CborObject.CborLong(size),
                new CborObject.CborLong(modified.toEpochSecond(ZoneOffset.UTC)),
                new CborObject.CborBoolean(isHidden),
                new CborObject.CborByteArray(thumbnail.orElse(new byte[0]))
        ));
    }

    @SuppressWarnings("unusable-by-js")
    public static FileProperties fromCbor(Cborable cbor) {
        List<? extends Cborable> elements = ((CborObject.CborList) cbor).value;
        String name = ((CborObject.CborString)elements.get(0)).value;
        String mimeType = ((CborObject.CborString)elements.get(1)).value;
        long size = ((CborObject.CborLong)elements.get(2)).value;
        long modified = ((CborObject.CborLong)elements.get(3)).value;
        boolean isHidden = ((CborObject.CborBoolean)elements.get(4)).value;
        byte[] thumb = ((CborObject.CborByteArray)elements.get(5)).value;
        Optional<byte[]> thumbnail = thumb.length == 0 ?
                Optional.empty() :
                Optional.of(thumb);

        return new FileProperties(name, mimeType, size, LocalDateTime.ofEpochSecond(modified, 0, ZoneOffset.UTC), isHidden, thumbnail);
    }

    @JsIgnore
    public FileProperties withSize(long newSize) {
        return new FileProperties(name, mimeType, newSize, modified, isHidden, thumbnail);
    }

    public FileProperties withModified(LocalDateTime modified) {
        return new FileProperties(name, mimeType, size, modified, isHidden, thumbnail);
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