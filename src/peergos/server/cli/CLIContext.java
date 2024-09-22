package peergos.server.cli;

import org.jline.terminal.Terminal;
import peergos.shared.user.UserContext;
import peergos.shared.util.*;

import java.nio.file.*;

public class CLIContext {
    public final Terminal terminal;
    public final UserContext userContext;
    public final String serverURL, username;
    public final Path home;
    public Path pwd, lpwd;

    public CLIContext(Terminal terminal, UserContext userContext, String serverURL, String username) {
        this.terminal = terminal;
        this.userContext = userContext;
        this.serverURL = serverURL;
        this.username = username;
        this.home = Paths.get("/" + username);
        this.pwd = Paths.get("/" + username);
        this.lpwd = Paths.get(System.getProperty("user.dir"));
    }
}
