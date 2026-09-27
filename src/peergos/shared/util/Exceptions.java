package peergos.shared.util;

import java.util.concurrent.*;

public class Exceptions {
    public static Throwable getRootCause(Throwable t) {
        Throwable cause = t.getCause();
        if (t instanceof ExecutionException)
            return getRootCause(cause);
        if (t instanceof RuntimeException && cause != null && cause != t)
            return getRootCause(cause);
        return t;
    }

    /** Whether a call failed because the server has no such endpoint.
     *
     *  Each client reports an http error in its own words: the java one names the status code, a
     *  browser gives us the status text, or the body of whatever generic 404 page answered. None of
     *  our own errors say any of these, and taking one for a missing endpoint only costs us the
     *  older, slower call.
     *
     *  A peergos server form encodes the message into a Trailer header, and only some clients decode
     *  it again: gwt.js and AndroidPoster.post both hand back the raw text, so the message can arrive
     *  as "Unimplemented+call%21". Matching the encoded spelling too is what lets the fallbacks fire
     *  in the browser and on Android, rather than surfacing the error to the user.
     */
    /** Whether the number appears on its own, rather than as part of a longer one, such as a byte count */
    private static boolean containsNumber(String text, String number) {
        for (int i = text.indexOf(number); i >= 0; i = text.indexOf(number, i + 1)) {
            boolean digitBefore = i > 0 && Character.isDigit(text.charAt(i - 1));
            int end = i + number.length();
            boolean digitAfter = end < text.length() && Character.isDigit(text.charAt(end));
            if (! digitBefore && ! digitAfter)
                return true;
        }
        return false;
    }

    public static boolean isUnimplemented(Throwable t) {
        String msg = getRootCause(t).getMessage();
        if (msg == null)
            return false;
        String decoded = msg.replace('+', ' ');
        return containsNumber(decoded, "404")
                || decoded.contains("Not Found")
                // the trailing ! is percent encoded, so match up to it
                || decoded.contains("Unimplemented call");
    }
}
