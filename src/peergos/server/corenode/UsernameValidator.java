package peergos.server.corenode;

import peergos.shared.corenode.*;

import java.util.regex.Pattern;

/**
 * Encapsulates CoreNode username rules.
 *
 */
public final class UsernameValidator {

    private static final Pattern VALID_USERNAME = Pattern.compile(Usernames.REGEX);

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
        return VALID_USERNAME.matcher(username).find();
    }

}
