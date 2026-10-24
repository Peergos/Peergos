package peergos.server.sync;

import java.io.IOException;

/** A file a sync was writing into was changed by someone else since the sync looked at it, or while it wrote */
public class ConcurrentChangeException extends IOException {

    public ConcurrentChangeException(String message) {
        super(message);
    }
}
