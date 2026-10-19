package peergos.shared.user;

import peergos.shared.corenode.*;
import peergos.shared.io.ipfs.*;

import java.util.*;

/** What a user logs in as (remove-pki §12.1): a bare username, meaning on the server they are talking to, or
 *  username@peerid, where the peerid names the server whose label the username is, as getMigrationId shows it.
 */
public class LoginName {
    public final String username;
    /** In the form the p2p proxy dials. */
    public final Optional<Cid> server;

    public LoginName(String username, Optional<Cid> server) {
        this.username = username;
        this.server = server;
    }

    public static LoginName parse(String login) {
        String trimmed = login.trim();
        int at = trimmed.lastIndexOf('@');
        if (at < 0)
            return new LoginName(trimmed, Optional.empty());
        String username = trimmed.substring(0, at);
        if (! username.matches(Usernames.REGEX))
            throw new IllegalStateException("Invalid username: " + username);
        try {
            return new LoginName(username, Optional.of(Cid.decodePeerId(trimmed.substring(at + 1))));
        } catch (RuntimeException e) {
            throw new IllegalStateException("Not a server id after the @ in " + trimmed);
        }
    }

    @Override
    public String toString() {
        return server.map(s -> username + "@" + s).orElse(username);
    }
}
