package peergos.shared.user.fs.archive;

import jsinterop.annotations.*;
import peergos.shared.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

import static peergos.shared.user.fs.archive.ZipFormat.*;

/** Changes a zip archive stored in Peergos without rewriting the whole of it.
 *
 *  Everything an archive needs to describe itself lives at its end, so adding, removing and
 *  renaming entries all come down to rewriting that tail: one ranged write that touches only the
 *  5 MiB chunks it lands in, however big the archive is.
 *
 *  Records for entries that are not being touched are carried over byte for byte, so whatever a
 *  previous writer put in them - unix permissions, extended timestamps, comments - survives.
 */
@JsType
public class ZipWriter {

    /** An index of a million entries is already refused, and their records are smaller than this.
     */
    private static final int MAX_CENTRAL_DIRECTORY = 64 * 1024 * 1024;
    private static final int ERASE_BUFFER = 64 * 1024;

    /** A file to add to an archive, or a directory to record in it.
     */
    @JsType
    public static class NewEntry {
        public final String path;
        @JsIgnore
        public final long size;
        public final LocalDateTime modified;
        @JsIgnore
        public final Supplier<CompletableFuture<AsyncReader>> data;
        public final boolean isDirectory;

        @JsIgnore
        public NewEntry(String path, long size, LocalDateTime modified, Supplier<CompletableFuture<AsyncReader>> data) {
            this(path, size, modified, data, false);
        }

        @JsIgnore
        public NewEntry(String path,
                        long size,
                        LocalDateTime modified,
                        Supplier<CompletableFuture<AsyncReader>> data,
                        boolean isDirectory) {
            this.path = ZipEntry.normalisePath(path)
                    .orElseThrow(() -> new IllegalStateException("Invalid path in an archive: " + path));
            this.size = size;
            this.modified = modified;
            this.data = data;
            this.isDirectory = isDirectory;
        }

        /** A directory only needs a record of its own when nothing in the archive implies it, which
         *  means an empty one: every other directory is implied by the paths of the files under it.
         */
        @JsIgnore
        public static NewEntry directory(String path, LocalDateTime modified) {
            return new NewEntry(path, 0, modified, () -> Futures.of(AsyncReader.build(new byte[0])), true);
        }
    }

    /** Add files to an archive, or replace ones already in it.
     */
    @JsIgnore
    public static CompletableFuture<FileWrapper> append(FileWrapper archive,
                                                        List<NewEntry> entries,
                                                        NetworkAccess network,
                                                        Crypto crypto,
                                                        ProgressConsumer<Long> monitor) {
        List<String> replaced = new ArrayList<>();
        for (NewEntry entry : entries)
            replaced.add(entry.path);
        return open(archive, network, crypto)
                .thenCompose(zip -> readDirectory(zip)
                        .thenCompose(directory -> measureAll(entries, 0, new ArrayList<>())
                                .thenCompose(measured -> {
                                    List<Record> kept = drop(directory.records, replaced);
                                    return writeTail(archive, zip, directory, kept, measured, network, crypto, monitor);
                                })));
    }

    /** Remove entries from an archive.
     *
     *  The record is dropped from the central directory, which is what makes the entry gone, and by
     *  default the bytes it left behind are overwritten in place. That costs a write of the chunks
     *  the entry spans, but a delete that leaves the data sitting there readable is not what anyone
     *  means by delete. Pass false to keep the old bytes and only drop the record.
     */
    @JsIgnore
    public static CompletableFuture<FileWrapper> remove(FileWrapper archive,
                                                        List<String> paths,
                                                        boolean eraseData,
                                                        NetworkAccess network,
                                                        Crypto crypto,
                                                        ProgressConsumer<Long> monitor) {
        return open(archive, network, crypto)
                .thenCompose(zip -> readDirectory(zip)
                        .thenCompose(directory -> {
                            List<ZipEntry> removed = expand(zip, paths);
                            List<String> removedPaths = new ArrayList<>();
                            for (ZipEntry entry : removed)
                                removedPaths.add(entry.path);
                            List<Record> kept = drop(directory.records, removedPaths);
                            if (kept.size() == directory.records.size() && removed.isEmpty())
                                return Futures.errored(new IllegalStateException("No such entry in the archive: " + paths));
                            // drop the records first: a crash after this leaves an archive that is
                            // still valid and no longer mentions the entry, rather than one whose
                            // directory points at bytes we have already erased
                            return writeTail(archive, zip, directory, kept, Collections.emptyList(), network, crypto, monitor)
                                    .thenCompose(updated -> eraseData ?
                                            eraseAll(updated, zip, removed, 0, network, crypto) :
                                            Futures.of(updated));
                        }));
    }

    /** Rename an entry, or a directory of them.
     *
     *  A name of the same length is patched where it stands, in the local header and in the
     *  directory record, which is two small writes whatever the entry weighs. A name of a different
     *  length would move every byte after it, so the entry is written again under the new name and
     *  the old one removed.
     */
    @JsIgnore
    public static CompletableFuture<FileWrapper> rename(FileWrapper archive,
                                                        String path,
                                                        String newName,
                                                        NetworkAccess network,
                                                        Crypto crypto,
                                                        ProgressConsumer<Long> monitor) {
        if (newName.contains("/") || newName.isEmpty() || newName.equals(".") || newName.equals(".."))
            return Futures.errored(new IllegalStateException("Invalid name: " + newName));
        return open(archive, network, crypto)
                .thenCompose(zip -> {
                    Optional<ZipEntry> target = zip.getIndex().get(path);
                    if (! target.isPresent())
                        return Futures.errored(new IllegalStateException("No such entry in the archive: " + path));
                    String parent = target.get().getParentPath();
                    return moveEntry(archive, path, parent.isEmpty() ? newName : parent + "/" + newName,
                            network, crypto, monitor);
                });
    }

    /** Move an entry, or a directory of them, to another path within the same archive.
     */
    @JsIgnore
    public static CompletableFuture<FileWrapper> moveEntry(FileWrapper archive,
                                                           String path,
                                                           String newPath,
                                                           NetworkAccess network,
                                                           Crypto crypto,
                                                           ProgressConsumer<Long> monitor) {
        Optional<String> normalised = ZipEntry.normalisePath(newPath);
        if (! normalised.isPresent() || normalised.get().isEmpty())
            return Futures.errored(new IllegalStateException("Invalid path in an archive: " + newPath));
        String renamed = normalised.get();
        return open(archive, network, crypto)
                .thenCompose(zip -> {
                    Optional<ZipEntry> target = zip.getIndex().get(path);
                    if (! target.isPresent())
                        return Futures.errored(new IllegalStateException("No such entry in the archive: " + path));
                    ZipEntry entry = target.get();
                    if (zip.getIndex().get(renamed).isPresent())
                        return Futures.errored(new IllegalStateException("Already in the archive: " + renamed));
                    if (entry.isDirectory)
                        return renameDirectory(archive, zip, entry, renamed, network, crypto, monitor);
                    if (utf8(entry.path).length == utf8(renamed).length)
                        return renameInPlace(archive, zip, entry, renamed, network, crypto, monitor);
                    return rewriteUnderNewName(archive, zip, entry, renamed, network, crypto, monitor);
                });
    }

    // the pieces

    private static CompletableFuture<ZipReader> open(FileWrapper archive, NetworkAccess network, Crypto crypto) {
        if (! archive.isWritable())
            return Futures.errored(new IllegalStateException("Cannot change an archive you can only read"));
        return ZipReader.open(archive, network, crypto);
    }

    private static class Record {
        final int offset, length;
        final String path;

        Record(int offset, int length, String path) {
            this.offset = offset;
            this.length = length;
            this.path = path;
        }
    }

    private static class Directory {
        final byte[] bytes;
        final List<Record> records;

        Directory(byte[] bytes, List<Record> records) {
            this.bytes = bytes;
            this.records = records;
        }
    }

    private static CompletableFuture<Directory> readDirectory(ZipReader zip) {
        long size = zip.getCentralDirectorySize();
        if (size > MAX_CENTRAL_DIRECTORY)
            return Futures.errored(new IllegalStateException("This archive's index is too big to change: " + size));
        return zip.getSource().get()
                .thenCompose(in -> ZipFormat.read(in, zip.getCentralDirectoryStart(), (int) size))
                .thenApply(bytes -> new Directory(bytes, parseRecords(bytes)));
    }

    private static List<Record> parseRecords(byte[] cd) {
        List<Record> records = new ArrayList<>();
        int pos = 0;
        while (cd.length - pos >= CENTRAL_HEADER_SIZE && i32(cd, pos) == CENTRAL_HEADER_SIG) {
            int nameLength = u16(cd, pos + 28);
            int length = CENTRAL_HEADER_SIZE + nameLength + u16(cd, pos + 30) + u16(cd, pos + 32);
            if (cd.length - pos < length)
                break;
            boolean utf8 = (u16(cd, pos + 8) & FLAG_UTF8_NAMES) != 0;
            String name = name(cd, pos + CENTRAL_HEADER_SIZE, nameLength, utf8);
            records.add(new Record(pos, length, ZipEntry.normalisePath(name).orElse("")));
            pos += length;
        }
        return records;
    }

    private static List<Record> drop(List<Record> records, List<String> paths) {
        Set<String> dropped = new HashSet<>(paths);
        List<Record> kept = new ArrayList<>();
        for (Record record : records) {
            if (! dropped.contains(record.path))
                kept.add(record);
        }
        return kept;
    }

    /** The entries a set of paths covers, so that removing a directory removes what is under it.
     */
    private static List<ZipEntry> expand(ZipReader zip, List<String> paths) {
        List<ZipEntry> res = new ArrayList<>();
        for (String path : paths) {
            Optional<ZipEntry> entry = zip.getIndex().get(path);
            if (! entry.isPresent())
                continue;
            if (entry.get().isDirectory) {
                String prefix = entry.get().path + "/";
                for (ZipEntry candidate : zip.getIndex().getEntries()) {
                    if (candidate.path.startsWith(prefix) && ! candidate.isDirectory)
                        res.add(candidate);
                }
            }
            // a directory that some writer recorded explicitly has a record of its own to drop
            res.add(entry.get());
        }
        return res;
    }

    private static class Measured {
        final NewEntry entry;
        final long crc, compressedSize;
        final int method;

        Measured(NewEntry entry, long crc, long compressedSize, int method) {
            this.entry = entry;
            this.crc = crc;
            this.compressedSize = compressedSize;
            this.method = method;
        }
    }

    /** Compress each new entry once to find out how big it becomes and what its CRC is: a ranged
     *  write has to know its own length before it starts, and deflate only tells you afterwards.
     */
    private static CompletableFuture<List<Measured>> measureAll(List<NewEntry> entries, int index, List<Measured> done) {
        if (index == entries.size())
            return Futures.of(done);
        return measure(entries.get(index))
                .thenCompose(measured -> {
                    done.add(measured);
                    return measureAll(entries, index + 1, done);
                });
    }

    private static CompletableFuture<Measured> measure(NewEntry entry) {
        if (entry.isDirectory)
            return Futures.of(new Measured(entry, 0, 0, STORED));
        DeflatingReader deflating = new DeflatingReader(entry.data, entry.size);
        byte[] scratch = new byte[64 * 1024];
        return countAll(deflating, scratch, 0)
                .thenApply(compressed -> {
                    long crc = deflating.crc();
                    deflating.close();
                    // deflate makes some things bigger, and the format lets us just store those
                    return compressed < entry.size ?
                            new Measured(entry, crc, compressed, DEFLATED) :
                            new Measured(entry, crc, entry.size, STORED);
                });
    }

    private static CompletableFuture<Long> countAll(AsyncReader reader, byte[] scratch, long total) {
        return reader.readIntoArray(scratch, 0, scratch.length)
                .thenCompose(read -> read <= 0 ?
                        Futures.of(total) :
                        countAll(reader, scratch, total + read));
    }

    private static CompletableFuture<FileWrapper> writeTail(FileWrapper archive,
                                                            ZipReader zip,
                                                            Directory directory,
                                                            List<Record> kept,
                                                            List<Measured> added,
                                                            NetworkAccess network,
                                                            Crypto crypto,
                                                            ProgressConsumer<Long> monitor) {
        long start = zip.getCentralDirectoryStart();
        long delta = zip.getOffsetDelta();
        List<ConcatReader.Part> parts = new ArrayList<>();
        List<byte[]> newRecords = new ArrayList<>();
        long at = start;
        for (Measured measured : added) {
            byte[] header = localHeader(measured);
            parts.add(ConcatReader.Part.of(header));
            NewEntry entry = measured.entry;
            if (! entry.isDirectory) {
                if (measured.method == STORED)
                    parts.add(new ConcatReader.Part(entry.size, entry.data));
                else
                    parts.add(new ConcatReader.Part(measured.compressedSize,
                            () -> Futures.of(new DeflatingReader(entry.data, entry.size))));
            }
            newRecords.add(centralRecord(measured, at - delta));
            at += header.length + measured.compressedSize;
        }

        long newDirectoryStart = at;
        int keptSize = 0;
        for (Record record : kept)
            keptSize += record.length;
        int addedSize = 0;
        for (byte[] record : newRecords)
            addedSize += record.length;
        byte[] newDirectory = new byte[keptSize + addedSize];
        int pos = 0;
        for (Record record : kept) {
            System.arraycopy(directory.bytes, record.offset, newDirectory, pos, record.length);
            pos += record.length;
        }
        for (byte[] record : newRecords) {
            System.arraycopy(record, 0, newDirectory, pos, record.length);
            pos += record.length;
        }
        parts.add(ConcatReader.Part.of(newDirectory));
        parts.add(ConcatReader.Part.of(endRecords(kept.size() + newRecords.size(), newDirectory.length,
                newDirectoryStart - delta, newDirectoryStart + newDirectory.length)));

        ConcatReader tail = new ConcatReader(parts);
        long end = start + tail.length();
        return clean(archive, network, crypto)
                .thenCompose(clean -> clean.overwriteSectionJS(tail, (int) (start >>> 32), (int) start,
                        (int) (end >>> 32), (int) end, Optional.empty(), network, crypto, monitor))
                .thenCompose(updated -> updated.getSize() > end ?
                        updated.truncate(end, network, crypto) :
                        Futures.of(updated));
    }

    private static CompletableFuture<FileWrapper> clean(FileWrapper file, NetworkAccess network, Crypto crypto) {
        if (! file.isDirty())
            return Futures.of(file);
        return network.synchronizer.applyComplexUpdate(file.owner(), file.signingPair(),
                        (version, committer) -> file.clean(version, committer, network, crypto)
                                .thenApply(cleaned -> cleaned.right))
                .thenCompose(version -> file.getUpdated(version, network));
    }

    // the bytes of the format

    /** A directory is a zip entry whose name ends in a slash, which is the only thing that says so.
     */
    private static byte[] entryName(NewEntry entry) {
        return utf8(entry.isDirectory ? entry.path + "/" : entry.path);
    }

    private static byte[] localHeader(Measured measured) {
        byte[] name = entryName(measured.entry);
        boolean zip64 = measured.entry.size > U32_MAX || measured.compressedSize > U32_MAX;
        int extraLength = zip64 ? 20 : 0;
        byte[] header = new byte[LOCAL_HEADER_SIZE + name.length + extraLength];
        int[] dos = millisToDosTime(measured.entry.modified.toEpochSecond(ZoneOffset.UTC) * 1000);
        writeU32(header, 0, LOCAL_HEADER_SIG & U32_MAX);
        writeU16(header, 4, zip64 ? 45 : 20);
        writeU16(header, 6, FLAG_UTF8_NAMES);
        writeU16(header, 8, measured.method);
        writeU16(header, 10, dos[0]);
        writeU16(header, 12, dos[1]);
        writeU32(header, 14, measured.crc);
        writeU32(header, 18, zip64 ? U32_MAX : measured.compressedSize);
        writeU32(header, 22, zip64 ? U32_MAX : measured.entry.size);
        writeU16(header, 26, name.length);
        writeU16(header, 28, extraLength);
        System.arraycopy(name, 0, header, LOCAL_HEADER_SIZE, name.length);
        if (zip64) {
            int at = LOCAL_HEADER_SIZE + name.length;
            writeU16(header, at, ZIP64_EXTRA_ID);
            writeU16(header, at + 2, 16);
            writeU64(header, at + 4, measured.entry.size);
            writeU64(header, at + 12, measured.compressedSize);
        }
        return header;
    }

    private static byte[] centralRecord(Measured measured, long localHeaderOffset) {
        byte[] name = entryName(measured.entry);
        boolean bigSizes = measured.entry.size > U32_MAX || measured.compressedSize > U32_MAX;
        boolean bigOffset = localHeaderOffset > U32_MAX;
        int extraLength = bigSizes || bigOffset ? 4 + (bigSizes ? 16 : 0) + (bigOffset ? 8 : 0) : 0;
        byte[] record = new byte[CENTRAL_HEADER_SIZE + name.length + extraLength];
        int[] dos = millisToDosTime(measured.entry.modified.toEpochSecond(ZoneOffset.UTC) * 1000);
        writeU32(record, 0, CENTRAL_HEADER_SIG & U32_MAX);
        writeU16(record, 4, 0x031E); // unix, version 3.0
        writeU16(record, 6, bigSizes || bigOffset ? 45 : 20);
        writeU16(record, 8, FLAG_UTF8_NAMES);
        writeU16(record, 10, measured.method);
        writeU16(record, 12, dos[0]);
        writeU16(record, 14, dos[1]);
        writeU32(record, 16, measured.crc);
        writeU32(record, 20, bigSizes ? U32_MAX : measured.compressedSize);
        writeU32(record, 24, bigSizes ? U32_MAX : measured.entry.size);
        writeU16(record, 28, name.length);
        writeU16(record, 30, extraLength);
        writeU32(record, 38, measured.entry.isDirectory ?
                0x41ED0010L : // 0755, and the ms-dos directory bit
                0x81A40000L); // 0644, as a regular file
        writeU32(record, 42, bigOffset ? U32_MAX : localHeaderOffset);
        System.arraycopy(name, 0, record, CENTRAL_HEADER_SIZE, name.length);
        if (extraLength > 0) {
            int at = CENTRAL_HEADER_SIZE + name.length;
            writeU16(record, at, ZIP64_EXTRA_ID);
            writeU16(record, at + 2, extraLength - 4);
            int value = at + 4;
            if (bigSizes) {
                writeU64(record, value, measured.entry.size);
                writeU64(record, value + 8, measured.compressedSize);
                value += 16;
            }
            if (bigOffset)
                writeU64(record, value, localHeaderOffset);
        }
        return record;
    }

    /** The end of central directory record, with the zip64 pair in front of it when the archive has
     *  outgrown what the original fields can say.
     */
    private static byte[] endRecords(int entries, long directorySize, long directoryOffset, long endPosition) {
        boolean zip64 = entries > U16_MAX || directorySize > U32_MAX || directoryOffset > U32_MAX;
        byte[] res = new byte[(zip64 ? ZIP64_EOCD_SIZE + ZIP64_LOCATOR_SIZE : 0) + EOCD_SIZE];
        int at = 0;
        if (zip64) {
            writeU32(res, 0, ZIP64_EOCD_SIG & U32_MAX);
            writeU64(res, 4, ZIP64_EOCD_SIZE - 12);
            writeU16(res, 12, 0x031E);
            writeU16(res, 14, 45);
            writeU64(res, 24, entries);
            writeU64(res, 32, entries);
            writeU64(res, 40, directorySize);
            writeU64(res, 48, directoryOffset);
            at = ZIP64_EOCD_SIZE;
            writeU32(res, at, ZIP64_LOCATOR_SIG & U32_MAX);
            writeU64(res, at + 8, endPosition);
            writeU32(res, at + 16, 1);
            at += ZIP64_LOCATOR_SIZE;
        }
        writeU32(res, at, EOCD_SIG & U32_MAX);
        writeU16(res, at + 8, zip64 ? U16_MAX : entries);
        writeU16(res, at + 10, zip64 ? U16_MAX : entries);
        writeU32(res, at + 12, zip64 ? U32_MAX : directorySize);
        writeU32(res, at + 16, zip64 ? U32_MAX : directoryOffset);
        return res;
    }

    // erasing what a removed entry left behind

    private static CompletableFuture<FileWrapper> eraseAll(FileWrapper archive,
                                                           ZipReader zip,
                                                           List<ZipEntry> entries,
                                                           int index,
                                                           NetworkAccess network,
                                                           Crypto crypto) {
        if (index == entries.size())
            return Futures.of(archive);
        ZipEntry entry = entries.get(index);
        if (entry.isDirectory)
            return eraseAll(archive, zip, entries, index + 1, network, crypto);
        return erase(archive, zip, entry, network, crypto)
                .thenCompose(updated -> eraseAll(updated, zip, entries, index + 1, network, crypto));
    }

    /** Overwrite an entry's local header and data with zeros, so that the name and the contents are
     *  no longer sitting in the archive to be found.
     */
    private static CompletableFuture<FileWrapper> erase(FileWrapper archive,
                                                        ZipReader zip,
                                                        ZipEntry entry,
                                                        NetworkAccess network,
                                                        Crypto crypto) {
        return zip.getSource().get()
                .thenCompose(in -> ZipFormat.read(in, entry.localHeaderOffset, LOCAL_HEADER_SIZE))
                .thenCompose(header -> {
                    if (i32(header, 0) != LOCAL_HEADER_SIG)
                        return Futures.of(archive);
                    long length = LOCAL_HEADER_SIZE + u16(header, 26) + u16(header, 28) + entry.compressedSize;
                    long start = entry.localHeaderOffset;
                    long end = Math.min(start + length, archive.getSize());
                    if (end <= start)
                        return Futures.of(archive);
                    return clean(archive, network, crypto)
                            .thenCompose(clean -> clean.overwriteSectionJS(new ZeroReader(end - start),
                                    (int) (start >>> 32), (int) start, (int) (end >>> 32), (int) end,
                                    Optional.empty(), network, crypto, x -> {}));
                });
    }

    private static class ZeroReader implements AsyncReader {
        private final long length;
        private long position = 0;

        ZeroReader(long length) {
            this.length = length;
        }

        @Override
        public CompletableFuture<Integer> readIntoArray(byte[] res, int offset, int length) {
            int toRead = (int) Math.min(length, this.length - position);
            Arrays.fill(res, offset, offset + toRead, (byte) 0);
            position += toRead;
            return Futures.of(toRead);
        }

        @Override
        public CompletableFuture<AsyncReader> seekJS(int high32, int low32) {
            position = (low32 & 0xFFFFFFFFL) | (((long) high32) << 32);
            return Futures.of(this);
        }

        @Override
        public CompletableFuture<AsyncReader> reset() {
            position = 0;
            return Futures.of(this);
        }

        @Override
        public void close() {}
    }

    // renaming

    private static CompletableFuture<FileWrapper> renameInPlace(FileWrapper archive,
                                                                ZipReader zip,
                                                                ZipEntry entry,
                                                                String renamed,
                                                                NetworkAccess network,
                                                                Crypto crypto,
                                                                ProgressConsumer<Long> monitor) {
        byte[] name = utf8(renamed);
        return readDirectory(zip).thenCompose(directory -> {
            List<Record> records = directory.records;
            Record record = null;
            for (Record candidate : records) {
                if (candidate.path.equals(entry.path))
                    record = candidate;
            }
            if (record == null)
                return Futures.errored(new IllegalStateException("No such entry in the archive: " + entry.path));
            // the name lives in two places, and a reader is entitled to look at either
            System.arraycopy(name, 0, directory.bytes, record.offset + CENTRAL_HEADER_SIZE, name.length);
            byte[] patched = new byte[directory.bytes.length];
            System.arraycopy(directory.bytes, 0, patched, 0, patched.length);
            long headerNameAt = entry.localHeaderOffset + LOCAL_HEADER_SIZE;
            return clean(archive, network, crypto)
                    .thenCompose(clean -> clean.overwriteSectionJS(AsyncReader.build(name),
                            (int) (headerNameAt >>> 32), (int) headerNameAt,
                            (int) ((headerNameAt + name.length) >>> 32), (int) (headerNameAt + name.length),
                            Optional.empty(), network, crypto, x -> {}))
                    .thenCompose(updated -> writeTail(updated, zip, new Directory(patched, records), records,
                            Collections.emptyList(), network, crypto, monitor));
        });
    }

    private static CompletableFuture<FileWrapper> rewriteUnderNewName(FileWrapper archive,
                                                                      ZipReader zip,
                                                                      ZipEntry entry,
                                                                      String renamed,
                                                                      NetworkAccess network,
                                                                      Crypto crypto,
                                                                      ProgressConsumer<Long> monitor) {
        NewEntry copy = new NewEntry(renamed, entry.size, entry.modified, () -> zip.read(entry));
        return append(archive, Collections.singletonList(copy), network, crypto, monitor)
                .thenCompose(updated -> remove(updated, Collections.singletonList(entry.path), true,
                        network, crypto, monitor));
    }

    private static CompletableFuture<FileWrapper> renameDirectory(FileWrapper archive,
                                                                  ZipReader zip,
                                                                  ZipEntry directory,
                                                                  String renamed,
                                                                  NetworkAccess network,
                                                                  Crypto crypto,
                                                                  ProgressConsumer<Long> monitor) {
        List<ZipEntry> children = new ArrayList<>();
        String prefix = directory.path + "/";
        for (ZipEntry candidate : zip.getIndex().getEntries()) {
            if (candidate.path.startsWith(prefix) && ! candidate.isDirectory)
                children.add(candidate);
        }
        List<NewEntry> moved = new ArrayList<>();
        List<String> old = new ArrayList<>();
        for (ZipEntry child : children) {
            moved.add(new NewEntry(renamed + "/" + child.path.substring(prefix.length()), child.size,
                    child.modified, () -> zip.read(child)));
            old.add(child.path);
        }
        if (moved.isEmpty())
            return Futures.errored(new IllegalStateException("Nothing to rename in " + directory.path));
        old.add(directory.path);
        return append(archive, moved, network, crypto, monitor)
                .thenCompose(updated -> remove(updated, old, true, network, crypto, monitor));
    }

    // what the web ui calls

    /** Add one file to an archive, reading it twice: once to find out how far it compresses, and
     *  again to write it, so the reader has to support reset.
     */
    public static CompletableFuture<FileWrapper> addFileJS(FileWrapper archive,
                                                           String path,
                                                           AsyncReader data,
                                                           int sizeHi,
                                                           int sizeLo,
                                                           double modifiedEpochMillis,
                                                           NetworkAccess network,
                                                           Crypto crypto,
                                                           ProgressConsumer<Long> monitor) {
        long size = (((long) sizeHi) << 32) | (sizeLo & 0xFFFFFFFFL);
        NewEntry entry = new NewEntry(path, size, when(modifiedEpochMillis), reread(data));
        return append(archive, Collections.singletonList(entry), network, crypto, monitor);
    }

    /** One entry to add, built where a long cannot cross into the browser. A path ending in a slash
     *  is a directory, which an archive only needs a record of when it is empty.
     */
    public static NewEntry newEntryJS(String path, AsyncReader data, double size, double modifiedEpochMillis) {
        if (path.endsWith("/"))
            return NewEntry.directory(path.substring(0, path.length() - 1), when(modifiedEpochMillis));
        return new NewEntry(path, (long) size, when(modifiedEpochMillis), reread(data));
    }

    /** An entry to add taken from a file in the drive rather than from the browser, which is what
     *  pasting into an archive does. The file is opened again for each of the two passes, so
     *  nothing has to be held while it is compressed and then written.
     */
    public static NewEntry entryFromFileJS(String path, FileWrapper file, NetworkAccess network, Crypto crypto) {
        FileProperties props = file.getFileProperties();
        if (props.isDirectory)
            return NewEntry.directory(path, props.modified);
        return new NewEntry(path, props.size, props.modified,
                () -> file.getInputStream(network, crypto, x -> {}).thenApply(reader -> (AsyncReader) reader));
    }

    /** An entry to add taken from an archive, which is what pasting between two of them does, or
     *  within one: reading the version we opened while a new one is written is what renaming an
     *  entry already relies on. The bytes are inflated and compressed again, so the entry arrives
     *  compressed however this archive would compress it rather than however the last one did.
     */
    public static NewEntry entryFromArchiveJS(String path, ZipReader source, ZipEntry entry) {
        if (entry.isDirectory)
            return NewEntry.directory(path, entry.modified);
        return new NewEntry(path, entry.size, entry.modified, () -> source.read(entry));
    }

    /** Add several entries in one rewrite of the tail, which is what uploading a directory does: a
     *  call per file would rewrite the tail once per file, and leave every earlier tail behind as
     *  dead weight in the archive.
     */
    public static CompletableFuture<FileWrapper> addFilesJS(FileWrapper archive,
                                                            NewEntry[] entries,
                                                            NetworkAccess network,
                                                            Crypto crypto,
                                                            ProgressConsumer<Long> monitor) {
        return append(archive, Arrays.asList(entries), network, crypto, monitor);
    }

    private static LocalDateTime when(double epochMillis) {
        long millis = (long) epochMillis;
        return LocalDateTime.ofEpochSecond(Math.floorDiv(millis, 1000L),
                (int) Math.floorMod(millis, 1000L) * 1_000_000, ZoneOffset.UTC);
    }

    /** Every entry is read twice, once to compress it and once to write it, so a reader handed in
     *  from outside has to go back to the start each time it is asked for.
     */
    private static Supplier<CompletableFuture<AsyncReader>> reread(AsyncReader data) {
        return () -> data.reset().thenApply(reset -> (AsyncReader) reset);
    }

    public static CompletableFuture<FileWrapper> removeJS(FileWrapper archive,
                                                          String[] paths,
                                                          boolean eraseData,
                                                          NetworkAccess network,
                                                          Crypto crypto,
                                                          ProgressConsumer<Long> monitor) {
        return remove(archive, Arrays.asList(paths), eraseData, network, crypto, monitor);
    }

    public static CompletableFuture<FileWrapper> renameJS(FileWrapper archive,
                                                          String path,
                                                          String newName,
                                                          NetworkAccess network,
                                                          Crypto crypto,
                                                          ProgressConsumer<Long> monitor) {
        return rename(archive, path, newName, network, crypto, monitor);
    }

    private ZipWriter() {}
}
