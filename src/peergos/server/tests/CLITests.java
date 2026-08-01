package peergos.server.tests;

import org.junit.Assert;
import org.junit.Test;
import peergos.server.cli.CLI;
import peergos.server.cli.ParsedCommand;
import peergos.shared.user.fs.AsyncReader;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

public class CLITests {

    @Test
    public void quoting() {
        CLI.fromLine("put dir\\ with\\ spaces.txt /me/target");
        CLI.fromLine("put \"dir with spaces\" /me/target");
        ParsedCommand cmd = CLI.fromLine("mkdir \"quotedpathwithnospaces\"");
        Assert.assertEquals(1, cmd.arguments.size());
        Assert.assertEquals("quotedpathwithnospaces", cmd.arguments.get(0));
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
