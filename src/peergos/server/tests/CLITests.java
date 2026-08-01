package peergos.server.tests;

import org.junit.Assert;
import org.junit.Test;
import peergos.server.cli.CLI;
import peergos.server.cli.Command;
import peergos.server.cli.ParsedCommand;
import peergos.server.simulation.Stat;
import peergos.shared.user.fs.AsyncReader;
import peergos.shared.user.fs.FileProperties;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public class CLITests {

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
}
