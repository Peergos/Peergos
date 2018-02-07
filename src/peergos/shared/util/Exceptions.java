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
}
