package peergos.shared.display;

import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.messaging.*;

public class MsgRef {

    public final TreeClock timestamp;
    public final Multihash hash;

    public MsgRef(TreeClock timestamp, Multihash hash) {
        this.timestamp = timestamp;
        this.hash = hash;
    }
}
