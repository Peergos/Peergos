package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.util.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;

public class CommandTests {

    private static Args parse(String... params) throws Exception {
        Path dir = Files.createTempDirectory("peergos-command");
        List<String> all = new ArrayList<>(Arrays.asList(params));
        all.addAll(List.of("-PEERGOS_PATH", dir.toString(), "-log-to-file", "false"));
        return Args.parse(all.toArray(new String[0]));
    }

    @Test
    public void subcommandDefaultsWinOverParents() throws Exception {
        AtomicReference<Args> ran = new AtomicReference<>();
        Command<Void> withPort = new Command<>("withport", "", a -> { ran.set(a); return null; },
                List.of(new Command.Arg("port", "", false, "8000")));
        Command<Void> withoutPort = new Command<>("withoutport", "", a -> { ran.set(a); return null; },
                List.of());
        Command<Void> root = new Command<>("root", "", a -> null,
                List.of(new Command.Arg("port", "", false, "7777"), new Command.Arg("other", "", false, "inherited")),
                List.of(withPort, withoutPort));

        root.main(parse("withport"));
        Assert.assertEquals("8000", ran.get().getArg("port"));
        Assert.assertEquals("inherited", ran.get().getArg("other"));

        root.main(parse("withoutport"));
        Assert.assertEquals("7777", ran.get().getArg("port"));

        root.main(parse("withport", "-port", "9000"));
        Assert.assertEquals("9000", ran.get().getArg("port"));
    }
}
