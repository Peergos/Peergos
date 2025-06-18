package peergos.shared.storage;

import jsinterop.annotations.JsMethod;
import peergos.shared.MaybeMultihash;
import peergos.shared.io.ipfs.Cid;

import java.util.Optional;

public class PointerCasException extends RuntimeException {

    public final MaybeMultihash existing, claimedExisting;
    public final Optional<Long> sequence;

    public PointerCasException(MaybeMultihash actualExisting, Optional<Long> actualSequence, MaybeMultihash claimedExisting) {
        super("PointerCAS:" + actualExisting + "," + actualSequence.map(Object::toString).orElse("") + "," + claimedExisting);
        this.existing = actualExisting;
        this.claimedExisting = claimedExisting;
        this.sequence = actualSequence;
    }

    @JsMethod
    public static PointerCasException fromString(String msg) {
        msg = msg.substring("PointerCAS:".length());
        String[] parts = msg.split(",");
        MaybeMultihash actualExisting = MaybeMultihash.of(Cid.decode(parts[0]));
        MaybeMultihash claimedExisting = MaybeMultihash.of(Cid.decode(parts[2]));
        Optional<Long> actualSequence = parts[1].length() == 0 ?
                Optional.empty() :
                Optional.of(Long.parseLong(parts[1]));
        return new PointerCasException(actualExisting, actualSequence, claimedExisting);
    }
}
