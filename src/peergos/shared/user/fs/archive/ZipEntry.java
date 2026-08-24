package peergos.shared.user.fs.archive;

import jsinterop.annotations.*;

import java.time.*;
import java.util.*;
import java.util.zip.CRC32;

import static peergos.shared.user.fs.archive.ZipFormat.*;

/** A single entry in a zip archive, as described by its central directory record.
 *
 *  The path is normalised: no leading or trailing slash, and no "." or ".." components. An entry
 *  whose name escapes the archive root is rejected at parse time rather than normalised into
 *  something else.
 */
@JsType
public class ZipEntry {

    public final String path;
    public final boolean isDirectory;
    @JsIgnore
    public final long size;
    @JsIgnore
    public final long compressedSize;
    public final int compressionMethod;
    @JsIgnore
    public final long crc32;
    @JsIgnore
    public final long localHeaderOffset;
    public final LocalDateTime modified;
    public final int flags;

    @JsIgnore
    public ZipEntry(String path,
                    boolean isDirectory,
                    long size,
                    long compressedSize,
                    int compressionMethod,
                    long crc32,
                    long localHeaderOffset,
                    LocalDateTime modified,
                    int flags) {
        this.path = path;
        this.isDirectory = isDirectory;
        this.size = size;
        this.compressedSize = compressedSize;
        this.compressionMethod = compressionMethod;
        this.crc32 = crc32;
        this.localHeaderOffset = localHeaderOffset;
        this.modified = modified;
        this.flags = flags;
    }

    /** A directory that no entry describes explicitly, but which entries below it imply.
     */
    @JsIgnore
    public static ZipEntry implicitDirectory(String path, LocalDateTime modified) {
        return new ZipEntry(path, true, 0, 0, STORED, 0, -1, modified, 0);
    }

    public String getName() {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    public String getParentPath() {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    /** Sizes are exact in a double up to 8 PiB, which is beyond any archive we can store.
     */
    public double getSize() {
        return size;
    }

    public boolean isEncrypted() {
        return (flags & FLAG_ENCRYPTED) != 0 || (flags & FLAG_STRONG_ENCRYPTION) != 0;
    }

    public boolean isSupported() {
        return ! isEncrypted() && (compressionMethod == STORED || compressionMethod == DEFLATED);
    }

    /** Whether this entry was written without knowing its size up front, so the local header's
     *  sizes are zero and the real ones follow the data.
     */
    @JsIgnore
    public boolean hasDataDescriptor() {
        return (flags & FLAG_DATA_DESCRIPTOR) != 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (! (o instanceof ZipEntry))
            return false;
        ZipEntry that = (ZipEntry) o;
        return isDirectory == that.isDirectory && size == that.size && localHeaderOffset == that.localHeaderOffset
                && path.equals(that.path);
    }

    @Override
    public int hashCode() {
        return Objects.hash(path, isDirectory, size, localHeaderOffset);
    }

    @Override
    public String toString() {
        return path + (isDirectory ? "/" : " (" + size + " bytes)");
    }

    /** Parse a central directory record.
     *
     * @param d buffer containing the whole record
     * @param offset index of the record's signature within d
     * @param prependDelta bytes of non zip data before the archive, to be added to stored offsets
     * @return the entry, or empty if its name escapes the archive root
     */
    @JsIgnore
    public static Optional<ZipEntry> fromCentralDirectory(byte[] d, int offset, long prependDelta) {
        if (i32(d, offset) != CENTRAL_HEADER_SIG)
            throw new IllegalStateException("Invalid zip central directory record");
        int flags = u16(d, offset + 8);
        int method = u16(d, offset + 10);
        int dosTime = u16(d, offset + 12);
        int dosDate = u16(d, offset + 14);
        long crc = u32(d, offset + 16);
        long compressedSize = u32(d, offset + 20);
        long size = u32(d, offset + 24);
        int nameLength = u16(d, offset + 28);
        int extraLength = u16(d, offset + 30);
        int externalAttributes = i32(d, offset + 38);
        long localHeaderOffset = u32(d, offset + 42);

        int nameStart = offset + CENTRAL_HEADER_SIZE;
        int extraStart = nameStart + nameLength;
        boolean utf8 = (flags & FLAG_UTF8_NAMES) != 0;
        String rawName = name(d, nameStart, nameLength, utf8);

        long millis = dosTimeToMillis(dosTime, dosDate);
        for (int i = extraStart; i + 4 <= extraStart + extraLength; ) {
            int id = u16(d, i);
            int length = u16(d, i + 2);
            int body = i + 4;
            if (body + length > extraStart + extraLength)
                break;
            if (id == ZIP64_EXTRA_ID) {
                int at = body;
                if (size == U32_MAX && at + 8 <= body + length) {
                    size = u64(d, at);
                    at += 8;
                }
                if (compressedSize == U32_MAX && at + 8 <= body + length) {
                    compressedSize = u64(d, at);
                    at += 8;
                }
                if (localHeaderOffset == U32_MAX && at + 8 <= body + length)
                    localHeaderOffset = u64(d, at);
            } else if (id == UNICODE_NAME_EXTRA_ID && ! utf8 && length > 5 && u8(d, body) == 1) {
                CRC32 nameCrc = new CRC32();
                nameCrc.update(d, nameStart, nameLength);
                if (nameCrc.getValue() == u32(d, body + 1))
                    rawName = name(d, body + 5, length - 5, true);
            } else if (id == EXTENDED_TIMESTAMP_EXTRA_ID && length >= 5 && (u8(d, body) & 1) != 0) {
                millis = i32(d, body + 1) * 1000L;
            }
            i = body + length;
        }

        // a directory is signalled by a trailing slash, or by the MS-DOS directory attribute
        boolean isDirectory = rawName.endsWith("/") || (externalAttributes & 0x10) != 0;
        Optional<String> normalised = normalisePath(rawName);
        if (! normalised.isPresent() || normalised.get().isEmpty())
            return Optional.empty();
        LocalDateTime modified = LocalDateTime.ofEpochSecond(millis / 1000, (int) (millis % 1000) * 1_000_000, ZoneOffset.UTC);
        return Optional.of(new ZipEntry(normalised.get(), isDirectory, isDirectory ? 0 : size,
                isDirectory ? 0 : compressedSize, method, crc, localHeaderOffset + prependDelta, modified, flags));
    }

    /** Strip the components a zip name may carry that a path within the archive cannot: a leading
     *  slash, a windows drive, and "." components. A ".." component would escape the archive, so
     *  such an entry is rejected.
     */
    @JsIgnore
    public static Optional<String> normalisePath(String rawName) {
        String name = rawName.replace('\\', '/');
        if (name.length() > 1 && name.charAt(1) == ':')
            name = name.substring(2);
        StringBuilder res = new StringBuilder();
        for (String component : name.split("/")) {
            if (component.isEmpty() || component.equals("."))
                continue;
            if (component.equals(".."))
                return Optional.empty();
            if (res.length() > 0)
                res.append("/");
            res.append(component);
        }
        return Optional.of(res.toString());
    }
}
