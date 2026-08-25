package peergos.server.tests;

import org.junit.*;
import peergos.server.util.*;
import peergos.shared.user.fs.*;
import peergos.shared.user.fs.archive.*;
import peergos.shared.util.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.CRC32;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

public class ZipTests {

    static {
        JavaInflate.init();
    }

    /** An in memory archive that records how much of itself has actually been read.
     */
    private static class CountingSource implements AsyncReader {
        public final byte[] data;
        public long bytesRead = 0;
        private int index = 0;

        public CountingSource(byte[] data) {
            this.data = data;
        }

        @Override
        public CompletableFuture<AsyncReader> seekJS(int high32, int low32) {
            long offset = (low32 & 0xFFFFFFFFL) | (((long) high32) << 32);
            if (offset < 0 || offset > data.length)
                throw new IllegalStateException("Seek outside of file: " + offset);
            index = (int) offset;
            return Futures.of(this);
        }

        @Override
        public CompletableFuture<Integer> readIntoArray(byte[] res, int offset, int length) {
            int toRead = Math.min(length, data.length - index);
            System.arraycopy(data, index, res, offset, toRead);
            index += toRead;
            bytesRead += toRead;
            return Futures.of(toRead);
        }

        @Override
        public CompletableFuture<AsyncReader> reset() {
            index = 0;
            return Futures.of(this);
        }

        @Override
        public void close() {}
    }

    private static class Archive {
        final byte[] data;
        final List<CountingSource> readers = new ArrayList<>();

        Archive(byte[] data) {
            this.data = data;
        }

        ZipReader open() {
            return ZipReader.open(() -> {
                CountingSource source = new CountingSource(data);
                readers.add(source);
                return Futures.of((AsyncReader) source);
            }, data.length).join();
        }

        long bytesRead() {
            return readers.stream().mapToLong(r -> r.bytesRead).sum();
        }
    }

    private static void fill(AsyncReader reader, byte[] res) {
        int offset = 0;
        while (offset < res.length) {
            int read = reader.readIntoArray(res, offset, res.length - offset).join();
            if (read <= 0)
                throw new IllegalStateException("Unexpected end of entry after " + offset + " bytes");
            offset += read;
        }
    }

    private static byte[] readAll(AsyncReader reader, long size) {
        byte[] res = new byte[(int) size];
        fill(reader, res);
        assertEquals("no more bytes after the entry", 0, (int) reader.readIntoArray(new byte[1], 0, 1).join());
        return res;
    }

    private static byte[] read(ZipReader zip, String path) {
        ZipEntry entry = zip.getIndex().get(path).orElseThrow(() -> new IllegalStateException("No entry " + path));
        return readAll(zip.read(entry).join(), entry.size);
    }

    private static byte[] random(int size, long seed) {
        byte[] res = new byte[size];
        new Random(seed).nextBytes(res);
        return res;
    }

    private static byte[] compressible(int size, long seed) {
        byte[] res = new byte[size];
        Random r = new Random(seed);
        for (int i = 0; i < size; i++)
            res[i] = (byte) ('a' + r.nextInt(4));
        return res;
    }

    private static Archive build(Map<String, byte[]> entries, boolean stored, String comment) {
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        try (ZipOutputStream zout = new ZipOutputStream(bout)) {
            if (comment != null)
                zout.setComment(comment);
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                java.util.zip.ZipEntry entry = new java.util.zip.ZipEntry(e.getKey());
                if (stored) {
                    entry.setMethod(ZipOutputStream.STORED);
                    entry.setSize(e.getValue().length);
                    entry.setCompressedSize(e.getValue().length);
                    CRC32 crc = new CRC32();
                    crc.update(e.getValue());
                    entry.setCrc(crc.getValue());
                }
                zout.putNextEntry(entry);
                zout.write(e.getValue());
                zout.closeEntry();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return new Archive(bout.toByteArray());
    }

    private static Map<String, byte[]> sampleEntries() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("readme.txt", "hello archive".getBytes());
        entries.put("logs/first.log", compressible(100_000, 1));
        entries.put("logs/nested/second.log", compressible(3, 2));
        entries.put("data.bin", random(50_000, 3));
        return entries;
    }

    @Test
    public void deflatedEntries() {
        Map<String, byte[]> entries = sampleEntries();
        ZipReader zip = build(entries, false, null).open();

        assertEquals(6, zip.getIndex().size()); // 4 files plus 2 implicit directories
        for (Map.Entry<String, byte[]> e : entries.entrySet())
            assertArrayEquals(e.getKey(), e.getValue(), read(zip, e.getKey()));
    }

    @Test
    public void storedEntries() {
        Map<String, byte[]> entries = sampleEntries();
        ZipReader zip = build(entries, true, null).open();

        for (Map.Entry<String, byte[]> e : entries.entrySet())
            assertArrayEquals(e.getKey(), e.getValue(), read(zip, e.getKey()));
    }

    @Test
    public void directoryTree() {
        ZipReader zip = build(sampleEntries(), false, null).open();

        assertEquals(Arrays.asList("logs", "data.bin", "readme.txt"),
                names(zip.listDirectory("")));
        assertEquals(Arrays.asList("nested", "first.log"), names(zip.listDirectory("logs")));
        assertEquals(Arrays.asList("second.log"), names(zip.listDirectory("logs/nested")));
        assertTrue(zip.getIndex().isDirectory("logs/nested"));
        assertFalse(zip.getIndex().isDirectory("readme.txt"));
        assertEquals(150_016, (long) zip.getIndex().getTotalSize());
    }

    private static List<String> names(List<ZipEntry> entries) {
        List<String> res = new ArrayList<>();
        for (ZipEntry e : entries)
            res.add(e.getName());
        return res;
    }

    @Test
    public void archiveComment() {
        StringBuilder comment = new StringBuilder();
        for (int i = 0; i < 60_000; i++)
            comment.append((char) ('a' + i % 26));
        ZipReader zip = build(sampleEntries(), false, comment.toString()).open();

        assertArrayEquals("hello archive".getBytes(), read(zip, "readme.txt"));
    }

    @Test
    public void emptyArchive() {
        ZipReader zip = build(new LinkedHashMap<>(), false, null).open();

        assertEquals(0, zip.getIndex().size());
        assertTrue(zip.listDirectory("").isEmpty());
    }

    @Test
    public void explicitDirectoryEntries() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("logs/", new byte[0]);
        entries.put("logs/first.log", "one".getBytes());
        ZipReader zip = build(entries, false, null).open();

        assertEquals(2, zip.getIndex().size());
        assertTrue(zip.getIndex().isDirectory("logs"));
        assertEquals(Arrays.asList("first.log"), names(zip.listDirectory("logs")));
        assertArrayEquals("one".getBytes(), read(zip, "logs/first.log"));
    }

    @Test
    public void dataPrependedToArchive() {
        Archive original = build(sampleEntries(), false, null);
        byte[] prefix = random(100_000, 7);
        byte[] combined = new byte[prefix.length + original.data.length];
        System.arraycopy(prefix, 0, combined, 0, prefix.length);
        System.arraycopy(original.data, 0, combined, prefix.length, original.data.length);
        ZipReader zip = new Archive(combined).open();

        assertEquals(6, zip.getIndex().size());
        assertArrayEquals("hello archive".getBytes(), read(zip, "readme.txt"));
        assertArrayEquals(sampleEntries().get("data.bin"), read(zip, "data.bin"));
    }

    @Test
    public void zip64ManyEntries() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (int i = 0; i < 70_000; i++)
            entries.put("f/" + i + ".txt", ("entry " + i).getBytes());
        ZipReader zip = build(entries, false, null).open();

        assertEquals(70_001, zip.getIndex().size()); // the files plus the implied directory f
        assertArrayEquals("entry 0".getBytes(), read(zip, "f/0.txt"));
        assertArrayEquals("entry 69999".getBytes(), read(zip, "f/69999.txt"));
    }

    @Test
    public void listingOnlyReadsTheIndex() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (int i = 0; i < 20; i++)
            entries.put("chunk" + i + ".bin", random(1024 * 1024, i));
        Archive archive = build(entries, true, null);
        assertTrue("archive is 20 MiB", archive.data.length > 20 * 1024 * 1024);

        ZipReader zip = archive.open();
        assertEquals(20, zip.listDirectory("").size());
        long afterListing = archive.bytesRead();
        assertTrue("listing read " + afterListing + " bytes of " + archive.data.length,
                afterListing < 128 * 1024);

        read(zip, "chunk19.bin");
        long afterOneEntry = archive.bytesRead() - afterListing;
        assertTrue("reading one entry read " + afterOneEntry + " bytes",
                afterOneEntry < 1024 * 1024 + 64 * 1024);
    }

    @Test
    public void readsFillTheWholeBuffer() {
        // callers expect a read to return everything they asked for, however many inflate calls
        // that takes, and the browser download path silently writes whatever it didn't get
        Map<String, byte[]> entries = new LinkedHashMap<>();
        byte[] text = compressible(1_000_000, 21);
        byte[] noisy = random(200_000, 22);
        entries.put("big.txt", text);
        entries.put("noise.bin", noisy);
        ZipReader zip = build(entries, false, null).open();

        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            ZipEntry entry = zip.getIndex().get(e.getKey()).get();
            AsyncReader reader = zip.read(entry).join();
            byte[] res = new byte[(int) entry.size];
            int read = reader.readIntoArray(res, 0, res.length).join();
            assertEquals(e.getKey() + ": one read returns everything asked for", res.length, read);
            assertArrayEquals(e.getKey(), e.getValue(), res);
        }
    }

    @Test
    public void resetBeforeReading() {
        // the upload path resets a reader before it reads anything from it
        Map<String, byte[]> entries = new LinkedHashMap<>();
        byte[] data = compressible(50_000, 31);
        entries.put("a.txt", data);
        ZipReader zip = build(entries, false, null).open();

        ZipEntry entry = zip.getIndex().get("a.txt").get();
        AsyncReader reader = zip.read(entry).join();
        reader.reset().join();
        assertArrayEquals(data, readAll(reader, entry.size));
    }

    @Test
    public void seekWithinAnEntry() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        byte[] deflated = compressible(200_000, 11);
        byte[] stored = random(100_000, 12);
        entries.put("deflated.txt", deflated);
        ZipReader zip = build(entries, false, null).open();
        entries.clear();
        entries.put("stored.bin", stored);
        ZipReader storedZip = build(entries, true, null).open();

        assertArrayEquals(Arrays.copyOfRange(deflated, 150_000, 150_100),
                readAt(zip, "deflated.txt", 150_000, 100));
        assertArrayEquals(Arrays.copyOfRange(deflated, 10, 110),
                readAt(zip, "deflated.txt", 10, 100)); // backwards, which restarts the stream
        assertArrayEquals(Arrays.copyOfRange(stored, 99_000, 99_100),
                readAt(storedZip, "stored.bin", 99_000, 100));
    }

    private static byte[] readAt(ZipReader zip, String path, long offset, int length) {
        AsyncReader reader = zip.read(path).join();
        byte[] res = new byte[length];
        reader.seek(offset).join();
        fill(reader, res);
        return res;
    }

    @Test
    public void corruptEntryFailsTheRead() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("stored.bin", random(10_000, 13));
        Archive archive = build(entries, true, null);
        archive.data[1000] ^= 0xFF; // inside the entry's data, so only the CRC can catch it

        ZipReader zip = archive.open();
        try {
            read(zip, "stored.bin");
            fail("should have failed the CRC check");
        } catch (Exception e) {
            assertTrue(e.getMessage(), e.getMessage().contains("CRC mismatch"));
        }
    }

    @Test
    public void pathsEscapingTheArchiveAreRejected() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("../escape.txt", "no".getBytes());
        entries.put("safe.txt", "yes".getBytes());
        ZipReader zip = build(entries, false, null).open();

        assertEquals(1, zip.getIndex().size());
        assertEquals(1, zip.getIndex().rejected);
        assertArrayEquals("yes".getBytes(), read(zip, "safe.txt"));
    }

    @Test
    public void notAZipFile() {
        try {
            new Archive(random(100_000, 17)).open();
            fail("should have rejected a non zip file");
        } catch (Exception e) {
            assertTrue(e.getMessage(), e.getMessage().contains("Not a zip archive"));
        }
    }

    @Test
    public void systemZipTool() throws Exception {
        Path dir = Files.createTempDirectory("peergos-zip-test");
        Files.write(dir.resolve("hello.txt"), "hello from zip".getBytes());
        Files.createDirectory(dir.resolve("sub"));
        Files.write(dir.resolve("sub").resolve("data.bin"), compressible(500_000, 19));
        Path archive = dir.resolve("test.zip");
        Assume.assumeTrue("the zip tool is installed",
                run(dir, "zip", "-r", archive.toString(), "hello.txt", "sub"));

        ZipReader zip = new Archive(Files.readAllBytes(archive)).open();
        assertEquals(Arrays.asList("sub", "hello.txt"), names(zip.listDirectory("")));
        assertArrayEquals("hello from zip".getBytes(), read(zip, "hello.txt"));
        assertArrayEquals(compressible(500_000, 19), read(zip, "sub/data.bin"));
    }

    @Test
    public void encryptedEntriesAreRefused() throws Exception {
        Path dir = Files.createTempDirectory("peergos-zip-test");
        Files.write(dir.resolve("secret.txt"), "classified".getBytes());
        Path archive = dir.resolve("encrypted.zip");
        Assume.assumeTrue("the zip tool is installed",
                run(dir, "zip", "-P", "hunter2", archive.toString(), "secret.txt"));

        ZipReader zip = new Archive(Files.readAllBytes(archive)).open();
        ZipEntry entry = zip.getIndex().get("secret.txt").get();
        assertTrue(entry.isEncrypted());
        assertFalse(entry.isSupported());
        try {
            zip.read(entry).join();
            fail("should have refused an encrypted entry");
        } catch (Exception e) {
            assertTrue(e.getMessage(), e.getMessage().contains("Encrypted zip entries are not supported"));
        }
    }

    private static boolean run(Path workingDir, String... command) {
        try {
            Process process = new ProcessBuilder(command)
                    .directory(workingDir.toFile())
                    .redirectErrorStream(true)
                    .start();
            process.getInputStream().readAllBytes();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
