package peergos.server.sync;

import java.io.IOException;

/** A file the local storage cannot hold under its name: it would be created under another, which
 *  the sync could not then find. Thrown by a SyncFilesystem's writes; a pass skips the file and says
 *  so, rather than stopping at it with everything after it undone. */
public class UnrepresentableNameException extends IOException {

    public UnrepresentableNameException(String message) {
        super(message);
    }
}
