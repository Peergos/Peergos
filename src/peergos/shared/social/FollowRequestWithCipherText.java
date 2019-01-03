package peergos.shared.social;

import jsinterop.annotations.*;
import peergos.shared.user.*;

public class FollowRequestWithCipherText {

    public final FollowRequest req;
    public final BlindFollowRequest cipher;

    public FollowRequestWithCipherText(FollowRequest req, BlindFollowRequest cipher) {
        this.req = req;
        this.cipher = cipher;
    }

    @JsMethod
    public EntryPoint getEntry() {
        return req.entry.get();
    }
}
