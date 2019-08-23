package peergos.server.cli;

import peergos.shared.user.UserContext;

import java.nio.file.Path;
import java.nio.file.Paths;

public class CLIContext {
    public final UserContext userContext;
    public final String serverURL, username;
    public Path pwd;

    public CLIContext(UserContext userContext, String serverURL, String username) {
        this.userContext = userContext;
        this.serverURL = serverURL;
        this.username = username;
        this.pwd = Paths.get("/" + username);
    }
}
