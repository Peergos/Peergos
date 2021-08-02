package peergos.shared.email;

import peergos.shared.user.fs.*;

public final class PendingAttachment {

    public final String uuid;
    public final AsyncReader source;
    public final int size;

    public PendingAttachment(String uuid, AsyncReader source, int size) {
        this.uuid = uuid;
        this.source = source;
        this.size = size;
    }
}
