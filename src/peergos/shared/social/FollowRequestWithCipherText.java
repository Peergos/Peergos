package peergos.shared.social;

import jsinterop.annotations.*;
import peergos.shared.user.*;

import java.util.*;

public class FollowRequestWithCipherText {

    public final FollowRequest req;
    public final BlindFollowRequest cipher;

    public FollowRequestWithCipherText(FollowRequest req, BlindFollowRequest cipher) {
        this.req = req;
        this.cipher = cipher;
    }

    public FollowRequestWithCipherText withEntryPoint(EntryPoint updated) {
        return new FollowRequestWithCipherText(new FollowRequest(Optional.of(updated), req.key), cipher);
    }

    @JsMethod
    public EntryPoint getEntry() {
        return req.entry.get();
    }
}
