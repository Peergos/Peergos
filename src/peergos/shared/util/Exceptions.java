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
     */
    public static boolean isUnimplemented(Throwable t) {
        String msg = getRootCause(t).getMessage();
        if (msg == null)
            return false;
        return msg.contains("404")
                || msg.contains("Not Found")
                || msg.contains("Unimplemented call!");
    }
}
