package peergos.server.tests;

import org.junit.Assert;
import org.junit.Test;
import peergos.server.cli.CLI;
import peergos.server.cli.ParsedCommand;

public class CLITests {

    @Test
    public void quoting() {
        CLI.fromLine("put dir\\ with\\ spaces.txt /me/target");
        CLI.fromLine("put \"dir with spaces\" /me/target");
        ParsedCommand cmd = CLI.fromLine("mkdir \"quotedpathwithnospaces\"");
        Assert.assertEquals(1, cmd.arguments.size());
        Assert.assertEquals("quotedpathwithnospaces", cmd.arguments.get(0));
    }
}
