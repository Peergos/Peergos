package peergos.server.corenode;

import peergos.shared.corenode.*;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.*;

/**
 * Encapsulates CoreNode username rules.
 *
 */
public final class UsernameValidator {

    private static final Pattern VALID_USERNAME = Pattern.compile(Usernames.REGEX);

    // These are for potential future interoperability and federation/bridging
    public static final Set<String> BANNED_USERNAMES =
            Stream.of("ipfs", "ipns", "root", "http", "https", "dns", "admin", "administrator", "support", "email", "mail", "www",
                    "web", "onion", "tls", "i2p", "ftp", "sftp", "file", "mailto", "wss", "xmpp", "ssh", "smtp", "imap",
                    "irc", "matrix", "twitter", "facebook", "instagram", "linkedin", "wechat", "tiktok", "reddit",
                    "snapchat", "qq", "whatsapp", "signal", "telegram", "matrix", "briar", "ssb", "mastodon",
                    "apple", "google", "pinterest",
                    "mls", "btc", "eth", "mnr", "zec", "friends", "followers", "username", "groups")
            .collect(Collectors.toSet());

    /** Username rules:
     * no _- at the end
     * allowed characters [a-z0-9_-]
     * no __ or -- or _- or -_ inside
     * no _- at the beginning
     * is 1-32 characters long
     * @param username
     * @return true iff username is a valid username.
     */
    public static boolean isValidUsername(String username) {
        return VALID_USERNAME.matcher(username).find() && ! BANNED_USERNAMES.contains(username);
    }

}
