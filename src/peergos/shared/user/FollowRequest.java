package peergos.shared.user;

import peergos.shared.crypto.symmetric.*;

import java.util.*;

public class FollowRequest {

    public final Optional<EntryPoint> entry;
    public final Optional<SymmetricKey> key;
    public final byte[] rawCipher;

    public FollowRequest(Optional<EntryPoint> entry, Optional<SymmetricKey> key, byte[] rawCipher) {
        this.entry = entry;
        this.key = key;
        this.rawCipher = rawCipher;
    }

    public boolean isAccepted() {
        return entry.isPresent();
    }

    public boolean isReciprocated() {
        return key.isPresent();
    }
}
