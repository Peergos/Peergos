package peergos.server.tests;

import org.junit.*;
import peergos.server.util.*;

import java.util.*;

public class ArgsTests {

    @Test
    public void parse() {
        Args daemon = Args.parse(new String[]{"daemon", "-useIPFS", "true"});
        Assert.assertTrue("command correct", daemon.commands().equals(Arrays.asList("daemon")));

        Args subcommand = Args.parse(new String[]{"server-msg", "show", "-useIPFS", "true"});
        Assert.assertTrue("command correct", subcommand.commands().equals(Arrays.asList("server-msg", "show")));
    }
}
