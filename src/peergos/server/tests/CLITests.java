package peergos.server.tests;

import org.junit.Assert;
import org.junit.Test;
import peergos.server.cli.ArchiveNavigator;
import peergos.server.cli.CLI;
import peergos.server.cli.Command;
import peergos.server.cli.ParsedCommand;
import peergos.server.simulation.FileSystem;
import peergos.server.simulation.Stat;
import peergos.server.util.JavaInflate;
import peergos.shared.user.fs.*;
import peergos.shared.user.fs.archive.ZipEntry;
import peergos.shared.user.fs.archive.ZipReader;
import peergos.shared.user.fs.transaction.FileUploadTransaction;
import peergos.shared.util.Futures;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipOutputStream;

public class CLITests {

    static {
        JavaInflate.init();
    }

    @Test
    public void quoting() {
        CLI.fromLine("put dir\\ with\\ spaces.txt /me/target");
        CLI.fromLine("put \"dir with spaces\" /me/target");
        ParsedCommand cmd = CLI.fromLine("mkdir \"quotedpathwithnospaces\"");
        Assert.assertEquals(1, cmd.arguments.size());
        Assert.assertEquals("quotedpathwithnospaces", cmd.arguments.get(0));
    }

    @Test
    public void flagParsing() {
        ParsedCommand shortFlag = CLI.fromLine("ls -l /me/photos");
        Assert.assertTrue(shortFlag.hasFlag(Command.Flag.LONG));
        Assert.assertEquals(List.of("/me/photos"), shortFlag.arguments);

        ParsedCommand longFlag = CLI.fromLine("ls --long");
        Assert.assertTrue(longFlag.hasFlag(Command.Flag.LONG));
        Assert.assertFalse(longFlag.hasArguments());

        ParsedCommand noFlag = CLI.fromLine("ls /me/photos");
        Assert.assertFalse(noFlag.hasFlag(Command.Flag.LONG));

        // the pre-existing double dash flags still parse
        ParsedCommand put = CLI.fromLine("put --skip-existing local.txt /me/remote.txt");
        Assert.assertTrue(put.hasFlag(Command.Flag.SKIP_EXISTING));
        Assert.assertFalse(put.hasFlag(Command.Flag.LONG));
        Assert.assertEquals(List.of("local.txt", "/me/remote.txt"), put.arguments);
    }

    @Test
    public void humanReadableSizes() {
        Assert.assertEquals("0 B", CLI.formatSize(0));
        Assert.assertEquals("1023 B", CLI.formatSize(1023));
        Assert.assertEquals("1.0 KiB", CLI.formatSize(1024));
        Assert.assertEquals("1.5 KiB", CLI.formatSize(1536));
        Assert.assertEquals("512 KiB", CLI.formatSize(512 * 1024));
        Assert.assertEquals("1.0 MiB", CLI.formatSize(1024 * 1024));
        Assert.assertEquals("2.5 GiB", CLI.formatSize((long) (2.5 * 1024 * 1024 * 1024)));
        // bigger than the largest unit stays in TiB rather than running off the end of the units
        Assert.assertEquals("5120 TiB", CLI.formatSize(5120L * 1024 * 1024 * 1024 * 1024));
    }

    @Test
    public void longFormat() {
        LocalDateTime modified = LocalDateTime.of(2026, 8, 1, 9, 5);
        Assert.assertEquals("-rw    1.0 KiB  2026-08-01 09:05  notes.txt",
                CLI.formatLong(stat("notes.txt", false, 1024, modified, true, true)));
        // a directory has no meaningful size
        Assert.assertEquals("dr-          -  2026-08-01 09:05  photos/",
                CLI.formatLong(stat("photos", true, 0, modified, true, false)));
    }

    private static Stat stat(String name, boolean isDirectory, long size, LocalDateTime modified,
                             boolean readable, boolean writable) {
        FileProperties props = new FileProperties(name, isDirectory, false, "", size, modified, modified,
                false, Optional.empty(), Optional.empty(), Optional.empty());
        return new Stat() {
            public String user() {
                return "me";
            }

            public FileProperties fileProperties() {
                return props;
            }

            public boolean isReadable() {
                return readable;
            }

            public boolean isWritable() {
                return writable;
            }
        };
    }

    private static String cat(String contents, int bufferSize) {
        byte[] data = contents.getBytes(StandardCharsets.UTF_8);
        StringWriter sink = new StringWriter();
        CLI.writeTextTo(new AsyncReader.ArrayBacked(data), data.length, new PrintWriter(sink), bufferSize);
        return sink.toString();
    }

    @Test
    public void catText() {
        Assert.assertEquals("", cat("", 1024));
        Assert.assertEquals("hello\nworld\n", cat("hello\nworld\n", 1024));
        // more content than a single read
        String big = "0123456789".repeat(1000);
        Assert.assertEquals(big, cat(big, 64));
    }

    @Test
    public void catSplitsMultiByteCharacters() {
        // '€' is 3 bytes in UTF-8, so every buffer size lands mid character for some of them
        String text = "a€b€c€d€e€f€g";
        for (int bufferSize = 1; bufferSize < 16; bufferSize++)
            Assert.assertEquals("buffer size " + bufferSize, text, cat(text, bufferSize));
    }

    @Test
    public void longFormatOfArchiveEntries() {
        LocalDateTime modified = LocalDateTime.of(2026, 8, 1, 9, 5);
        Assert.assertEquals("-rw    1.0 KiB  2026-08-01 09:05  notes.txt",
                CLI.formatLong(new ZipEntry("logs/notes.txt", false, 1024, 300, 8, 0, 0, modified, 0), true));
        Assert.assertEquals("drw          -  2026-08-01 09:05  logs/",
                CLI.formatLong(new ZipEntry("logs", true, 0, 0, 0, 0, 0, modified, 0), true));
        // an archive shared read only holds nothing writable
        Assert.assertEquals("-r-    1.0 KiB  2026-08-01 09:05  notes.txt",
                CLI.formatLong(new ZipEntry("logs/notes.txt", false, 1024, 300, 8, 0, 0, modified, 0), false));
        // an encrypted entry can be listed, and removed, but not read
        Assert.assertEquals("--w    1.0 KiB  2026-08-01 09:05  secret.txt",
                CLI.formatLong(new ZipEntry("secret.txt", false, 1024, 1024, 8, 0, 0, modified, 1), true));
    }

    private static byte[] archive(Map<String, byte[]> entries) {
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        try (ZipOutputStream zout = new ZipOutputStream(bout)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zout.putNextEntry(new java.util.zip.ZipEntry(e.getKey()));
                zout.write(e.getValue());
                zout.closeEntry();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return bout.toByteArray();
    }

    private static FakeFileSystem fakeFilesystem() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("readme.txt", "hello archive".getBytes(StandardCharsets.UTF_8));
        entries.put("logs/first.log", "log one".getBytes(StandardCharsets.UTF_8));
        FakeFileSystem fs = new FakeFileSystem();
        fs.mkdir(Paths.get("/me/backups"));
        fs.write(Paths.get("/me/backups/data.zip"), archive(entries));
        fs.write(Paths.get("/me/backups/notes.txt"), "not an archive".getBytes(StandardCharsets.UTF_8));
        return fs;
    }

    @Test
    public void archivePathResolution() {
        ArchiveNavigator archives = new ArchiveNavigator(fakeFilesystem());

        ArchiveNavigator.Target dir = archives.resolve(Paths.get("/me/backups")).get();
        Assert.assertFalse(dir.isArchive());
        Assert.assertFalse(dir.isInArchive());

        ArchiveNavigator.Target file = archives.resolve(Paths.get("/me/backups/notes.txt")).get();
        Assert.assertFalse(file.isArchive());

        // the archive itself is not a path inside an archive, so cat and get still see a normal file
        ArchiveNavigator.Target zip = archives.resolve(Paths.get("/me/backups/data.zip")).get();
        Assert.assertTrue(zip.isArchive());
        Assert.assertFalse(zip.isInArchive());
        Assert.assertEquals("", zip.entry);

        ArchiveNavigator.Target entry = archives.resolve(Paths.get("/me/backups/data.zip/logs/first.log")).get();
        Assert.assertTrue(entry.isInArchive());
        Assert.assertEquals(Paths.get("/me/backups/data.zip"), entry.path);
        Assert.assertEquals("logs/first.log", entry.entry);
        Assert.assertEquals(Paths.get("/me/backups/data.zip/logs/first.log"), entry.fullPath());

        Assert.assertFalse(archives.resolve(Paths.get("/me/backups/missing.txt")).isPresent());
        Assert.assertFalse(archives.resolve(Paths.get("/me/backups/notes.txt/nope")).isPresent());
    }

    @Test
    public void archiveEntryReads() {
        ArchiveNavigator archives = new ArchiveNavigator(fakeFilesystem());
        ArchiveNavigator.Target target = archives.resolve(Paths.get("/me/backups/data.zip/readme.txt")).get();

        ZipEntry entry = archives.entry(target);
        Assert.assertEquals(13, entry.size);
        byte[] contents = new byte[(int) entry.size];
        archives.open(target).read(entry).join().readIntoArray(contents, 0, contents.length).join();
        Assert.assertEquals("hello archive", new String(contents, StandardCharsets.UTF_8));

        ArchiveNavigator.Target missing = archives.resolve(Paths.get("/me/backups/data.zip/nope.txt")).get();
        try {
            archives.entry(missing);
            Assert.fail("should have failed on a missing entry");
        } catch (Exception e) {
            Assert.assertTrue(e.getMessage(), e.getMessage().contains("No such entry"));
        }
    }

    @Test
    public void archiveIndexIsCachedUntilTheFileChanges() {
        FakeFileSystem fs = fakeFilesystem();
        ArchiveNavigator archives = new ArchiveNavigator(fs);
        ArchiveNavigator.Target first = archives.resolve(Paths.get("/me/backups/data.zip/readme.txt")).get();
        ZipReader zip = archives.open(first);

        ArchiveNavigator.Target again = archives.resolve(Paths.get("/me/backups/data.zip/logs")).get();
        Assert.assertSame(zip, archives.open(again));

        Map<String, byte[]> changed = new LinkedHashMap<>();
        changed.put("readme.txt", "a different archive".getBytes(StandardCharsets.UTF_8));
        fs.write(Paths.get("/me/backups/data.zip"), archive(changed));
        ArchiveNavigator.Target afterChange = archives.resolve(Paths.get("/me/backups/data.zip")).get();
        Assert.assertNotSame(zip, archives.open(afterChange));
        Assert.assertEquals(1, archives.open(afterChange).getIndex().size());
    }

    /** The parts of a Peergos filesystem that resolving and reading an archive uses.
     */
    private static class FakeFileSystem implements FileSystem {
        private final Map<Path, byte[]> files = new HashMap<>();
        private final Set<Path> dirs = new HashSet<>(Set.of(Paths.get("/"), Paths.get("/me")));

        public String user() {
            return "me";
        }

        public byte[] read(Path path, BiConsumer<Long, Long> progressConsumer) {
            return files.get(path);
        }

        public AsyncReader reader(Path path) {
            return new AsyncReader.ArrayBacked(files.get(path));
        }

        public void write(Path path, byte[] data) {
            files.put(path, data);
        }

        public void mkdir(Path path) {
            dirs.add(path);
        }

        public Stat stat(Path path) {
            LocalDateTime modified = LocalDateTime.of(2026, 8, 1, 9, 5);
            boolean isDirectory = dirs.contains(path);
            if (! isDirectory && ! files.containsKey(path))
                throw new IllegalStateException("No such path " + path);
            byte[] data = isDirectory ? new byte[0] : files.get(path);
            String name = path.getFileName().toString();
            FileProperties props = new FileProperties(name, isDirectory, false,
                    isDirectory ? "" : MimeTypes.calculateMimeType(data, name),
                    isDirectory ? 0 : data.length, modified, modified, false,
                    Optional.empty(), Optional.empty(), Optional.empty());
            return new Stat() {
                public String user() {
                    return "me";
                }

                public FileProperties fileProperties() {
                    return props;
                }

                public boolean isReadable() {
                    return true;
                }

                public boolean isWritable() {
                    return true;
                }
            };
        }

        public List<Path> ls(Path path, boolean showHidden) {
            return Stream.concat(dirs.stream(), files.keySet().stream())
                    .filter(p -> path.equals(p.getParent()))
                    .sorted()
                    .collect(Collectors.toList());
        }

        public void write(Path path, AsyncReader data, long size, Consumer<Long> progressConsumer, boolean resumeUpload) {
            throw new UnsupportedOperationException();
        }

        public void writeSubtree(Path path, Stream<FileWrapper.FolderUploadProperties> folders,
                                 Function<FileUploadTransaction, CompletableFuture<Boolean>> resumeFile) {
            throw new UnsupportedOperationException();
        }

        public void modify(Path path, byte[] data, Consumer<Long> progressConsumer) {
            throw new UnsupportedOperationException();
        }

        public void delete(Path path) {
            throw new UnsupportedOperationException();
        }

        public void grant(Path path, String user, Permission permission) {
            throw new UnsupportedOperationException();
        }

        public void revoke(Path path, String user, Permission permission) {
            throw new UnsupportedOperationException();
        }

        public List<String> getSharees(Path path, Permission permission) {
            throw new UnsupportedOperationException();
        }

        public void follow(FileSystem other, boolean reciprocate) {
            throw new UnsupportedOperationException();
        }

        public Path getRandomSharedPath(Random random, Permission permission, String sharee) {
            throw new UnsupportedOperationException();
        }
    }
}
