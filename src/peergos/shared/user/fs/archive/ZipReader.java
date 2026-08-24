package peergos.shared.user.fs.archive;

import jsinterop.annotations.*;
import peergos.shared.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

import static peergos.shared.user.fs.archive.ZipFormat.*;

/** Reads a zip archive stored in Peergos without ever reading the whole file.
 *
 *  A zip's index lives at its end, so listing an archive of any size costs a read of its tail plus
 *  a read of its central directory, and opening one entry costs a seek to that entry's local header
 *  plus a read of only that entry's bytes.
 */
@JsType
public class ZipReader {

    /** An index of this many entries already costs over 100 MB of RAM, which is where browsing
     *  stops being a sensible thing to offer.
     */
    public static final int MAX_ENTRIES = 500_000;
    private static final int BLOCK = 64 * 1024;

    private final Supplier<CompletableFuture<AsyncReader>> source;
    private final long fileSize;
    private final ZipIndex index;

    @JsIgnore
    public ZipReader(Supplier<CompletableFuture<AsyncReader>> source, long fileSize, ZipIndex index) {
        this.source = source;
        this.fileSize = fileSize;
        this.index = index;
    }

    public ZipIndex getIndex() {
        return index;
    }

    @JsIgnore
    public List<ZipEntry> listDirectory(String path) {
        return index.listDirectory(path);
    }

    /** A reader over the decompressed contents of a single entry.
     */
    @JsIgnore
    public CompletableFuture<AsyncReader> read(ZipEntry entry) {
        if (entry.isDirectory)
            return Futures.errored(new IllegalStateException("Cannot read a directory in an archive: " + entry.path));
        if (entry.isEncrypted())
            return Futures.errored(new IllegalStateException("Encrypted zip entries are not supported: " + entry.path));
        if (! entry.isSupported())
            return Futures.errored(new IllegalStateException("Unsupported zip compression method "
                    + entry.compressionMethod + " in " + entry.path));

        return source.get().thenCompose(in -> ZipFormat.read(in, entry.localHeaderOffset, LOCAL_HEADER_SIZE)
                .thenApply(header -> {
                    if (i32(header, 0) != LOCAL_HEADER_SIG)
                        throw new IllegalStateException("Invalid local header for zip entry " + entry.path);
                    // the local header, not the central directory, describes the bytes that follow it
                    int method = u16(header, 8);
                    int nameLength = u16(header, 26);
                    int extraLength = u16(header, 28);
                    long dataStart = entry.localHeaderOffset + LOCAL_HEADER_SIZE + nameLength + extraLength;
                    if (dataStart + entry.compressedSize > fileSize)
                        throw new IllegalStateException("Truncated zip entry " + entry.path);
                    RegionReader region = new RegionReader(in, dataStart, entry.compressedSize);
                    AsyncReader data = method == STORED ? region : new InflatingReader(region, entry.size);
                    return new CrcVerifyingReader(data, entry.size, entry.crc32);
                }));
    }

    /** Open an archive stored in Peergos.
     */
    public static CompletableFuture<ZipReader> openJS(FileWrapper file, NetworkAccess network, Crypto crypto) {
        return open(file, network, crypto);
    }

    public ZipEntry[] listDirectoryJS(String path) {
        List<ZipEntry> children = index.listDirectory(path);
        return children.toArray(new ZipEntry[children.size()]);
    }

    /** The entry at a path within the archive, or null if there isn't one.
     */
    public ZipEntry getEntryJS(String path) {
        return index.get(path).orElse(null);
    }

    public CompletableFuture<AsyncReader> readJS(ZipEntry entry) {
        return read(entry);
    }

    @JsIgnore
    public CompletableFuture<AsyncReader> read(String path) {
        Optional<ZipEntry> entry = index.get(path);
        if (! entry.isPresent())
            return Futures.errored(new IllegalStateException("No such entry in archive: " + path));
        return read(entry.get());
    }

    @JsIgnore
    public static CompletableFuture<ZipReader> open(FileWrapper file, NetworkAccess network, Crypto crypto) {
        return open(() -> file.getInputStream(network, crypto, x -> {}).thenApply(r -> (AsyncReader) r), file.getSize());
    }

    /** Parse an archive's index.
     *
     * @param source supplies an independent reader over the archive, one per concurrent entry read
     * @param fileSize the size of the archive
     */
    @JsIgnore
    public static CompletableFuture<ZipReader> open(Supplier<CompletableFuture<AsyncReader>> source, long fileSize) {
        if (fileSize < EOCD_SIZE)
            return Futures.errored(new IllegalStateException("File is too small to be a zip archive"));
        int tailLength = (int) Math.min(fileSize, EOCD_SIZE + MAX_COMMENT_SIZE);
        long tailStart = fileSize - tailLength;
        return source.get().thenCompose(in -> ZipFormat.read(in, tailStart, tailLength)
                .thenCompose(tail -> {
                    int eocd = findEocd(tail);
                    if (eocd < 0)
                        throw new IllegalStateException("Not a zip archive: no end of central directory record");
                    return locateCentralDirectory(in, tail, tailStart, eocd)
                            .thenCompose(cd -> {
                                if (cd.entries > MAX_ENTRIES)
                                    throw new IllegalStateException("Too many entries to browse this archive: " + cd.entries);
                                if (cd.size < 0 || cd.start < 0 || cd.start + cd.size > fileSize)
                                    throw new IllegalStateException("Corrupt zip archive: invalid central directory");
                                int[] rejected = new int[1];
                                return parseCentralDirectory(in, cd, rejected)
                                        .thenApply(entries -> new ZipReader(source, fileSize,
                                                ZipIndex.build(entries, rejected[0])));
                            });
                }));
    }

    /** Where the central directory actually is, and how big it is.
     *
     *  The position is derived from where the end records are rather than from the offset they
     *  record, so that an archive with data prepended - a self extracting exe, or a concatenation -
     *  still resolves. Every stored offset is then shifted by the same delta.
     */
    private static class Directory {
        final long start, size, entries, delta;

        Directory(long start, long size, long entries, long recordedOffset) {
            this.start = start;
            this.size = size;
            this.entries = entries;
            this.delta = start - recordedOffset;
        }
    }

    private static int findEocd(byte[] tail) {
        for (int i = tail.length - EOCD_SIZE; i >= 0; i--) {
            if (i32(tail, i) == EOCD_SIG && i + EOCD_SIZE + u16(tail, i + 20) == tail.length)
                return i;
        }
        // some writers record a comment length that doesn't reach the end of the file
        for (int i = tail.length - EOCD_SIZE; i >= 0; i--) {
            if (i32(tail, i) == EOCD_SIG)
                return i;
        }
        return -1;
    }

    private static CompletableFuture<Directory> locateCentralDirectory(AsyncReader in,
                                                                      byte[] tail,
                                                                      long tailStart,
                                                                      int eocd) {
        long entries = u16(tail, eocd + 10);
        long size = u32(tail, eocd + 12);
        long offset = u32(tail, eocd + 16);
        boolean needsZip64 = size == U32_MAX || offset == U32_MAX;

        int locator = eocd - ZIP64_LOCATOR_SIZE;
        if (locator < 0 || i32(tail, locator) != ZIP64_LOCATOR_SIG) {
            if (needsZip64)
                throw new IllegalStateException("Corrupt zip archive: zip64 fields without a zip64 locator");
            return Futures.of(new Directory(tailStart + eocd - size, size, entries, offset));
        }

        // the zip64 end record normally sits immediately before its locator, so it is already read
        int inTail = locator - ZIP64_EOCD_SIZE;
        if (inTail >= 0 && i32(tail, inTail) == ZIP64_EOCD_SIG)
            return Futures.of(parseZip64Eocd(tail, inTail, tailStart + inTail));
        long recorded = u64(tail, locator + 8);
        return ZipFormat.read(in, recorded, ZIP64_EOCD_SIZE)
                .thenApply(record -> {
                    if (i32(record, 0) != ZIP64_EOCD_SIG)
                        throw new IllegalStateException("Corrupt zip archive: no zip64 end of central directory record");
                    return parseZip64Eocd(record, 0, recorded);
                });
    }

    private static Directory parseZip64Eocd(byte[] d, int at, long position) {
        long entries = u64(d, at + 32);
        long size = u64(d, at + 40);
        long offset = u64(d, at + 48);
        return new Directory(position - size, size, entries, offset);
    }

    private static CompletableFuture<List<ZipEntry>> parseCentralDirectory(AsyncReader in, Directory cd, int[] rejected) {
        return in.seek(cd.start)
                .thenCompose(at -> readRecords(at, cd.size, cd.delta, new byte[0], new ArrayList<>(), rejected));
    }

    /** Parse the central directory in fixed size blocks, so that only the resulting index scales
     *  with the number of entries.
     */
    private static CompletableFuture<List<ZipEntry>> readRecords(AsyncReader in,
                                                                 long remaining,
                                                                 long delta,
                                                                 byte[] carry,
                                                                 List<ZipEntry> entries,
                                                                 int[] rejected) {
        if (remaining == 0 && carry.length < CENTRAL_HEADER_SIZE)
            return Futures.of(entries);
        int toRead = (int) Math.min(BLOCK, remaining);
        byte[] buf = new byte[carry.length + toRead];
        System.arraycopy(carry, 0, buf, 0, carry.length);
        return ZipFormat.readFully(in, buf, carry.length, toRead)
                .thenCompose(full -> {
                    int pos = 0;
                    while (buf.length - pos >= CENTRAL_HEADER_SIZE) {
                        if (i32(buf, pos) != CENTRAL_HEADER_SIG)
                            return Futures.of(entries); // the end records follow the last entry
                        int recordLength = CENTRAL_HEADER_SIZE + u16(buf, pos + 28) + u16(buf, pos + 30) + u16(buf, pos + 32);
                        if (buf.length - pos < recordLength)
                            break; // a record spanning blocks, carried over into the next read
                        Optional<ZipEntry> entry = ZipEntry.fromCentralDirectory(buf, pos, delta);
                        if (entry.isPresent())
                            entries.add(entry.get());
                        else
                            rejected[0]++;
                        if (entries.size() > MAX_ENTRIES)
                            throw new IllegalStateException("Too many entries to browse this archive");
                        pos += recordLength;
                    }
                    if (toRead == 0 && pos == 0)
                        return Futures.of(entries); // trailing bytes that aren't a whole record
                    return readRecords(in, remaining - toRead, delta, Arrays.copyOfRange(buf, pos, buf.length),
                            entries, rejected);
                });
    }
}
