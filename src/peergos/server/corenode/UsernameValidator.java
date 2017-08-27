package peergos.server.corenode;

import java.util.regex.Pattern;

/**
 * Encapsulates CoreNode username rules.
 *
 *
 */
public final class UsernameValidator {

    final static Pattern VALID_USERNAME = Pattern.compile("^(?=.{1,32}$)(?![_.])(?!.*[_.]{2})[a-zA-Z0-9._]+(?<![_.])$");

    /** Username rules:
     * no _ or . at the end
     * allowed characters [a-zA-Z0-9._]
     * no __ or _. or ._ or .. inside
     * no _ or . at the beginning
     * is 1-32 characters long
     * @param username
     * @return true iff username is a valid username.
     */
    public static boolean isValidUsername(String username) {
        return VALID_USERNAME.matcher(username).find();
    }

}
