package peergos.shared.social;

import jsinterop.annotations.*;
import peergos.shared.user.*;

import java.util.*;

public class FollowRequestWithCipherText {

    public final FollowRequest req;
    public final BlindFollowRequest cipher;
    // who passed on the introduction this request was sent to, as named in our link store, so it can't be faked
    public final Optional<String> introducedBy;

    public FollowRequestWithCipherText(FollowRequest req, BlindFollowRequest cipher) {
        this(req, cipher, Optional.empty());
    }

    public FollowRequestWithCipherText(FollowRequest req, BlindFollowRequest cipher, Optional<String> introducedBy) {
        this.req = req;
        this.cipher = cipher;
        this.introducedBy = introducedBy;
    }

    public FollowRequestWithCipherText withEntryPoint(EntryPoint updated) {
        return new FollowRequestWithCipherText(req.withEntry(updated), cipher, introducedBy);
    }

    /** The name of whoever introduced the sender, or null */
    @JsMethod
    public String getIntroducer() {
        return introducedBy.orElse(null);
    }

    @JsMethod
    public EntryPoint getEntry() {
        return req.entry.get();
    }
}
