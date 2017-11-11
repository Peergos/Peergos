package peergos.server.corenode;

import java.util.regex.Pattern;

/**
 * Encapsulates CoreNode username rules.
 *
 *
 */
public final class UsernameValidator {

    final static Pattern VALID_USERNAME = Pattern.compile("^(?=.{1,32}$)(?![_])(?!.*[_]{2})[a-z0-9_]+(?<![_])$");

    /** Username rules:
     * no _ at the end
     * allowed characters [a-z0-9_]
     * no __ inside
     * no _ at the beginning
     * is 1-32 characters long
     * @param username
     * @return true iff username is a valid username.
     */
    public static boolean isValidUsername(String username) {
        return VALID_USERNAME.matcher(username).find();
    }

}
