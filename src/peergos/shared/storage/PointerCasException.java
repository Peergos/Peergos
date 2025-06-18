package peergos.shared.storage;

import peergos.shared.MaybeMultihash;

import java.util.Optional;

public class PointerCasException extends RuntimeException {

    public final MaybeMultihash existing, claimedExisting;
    public final Optional<Long> sequence;

    public PointerCasException(MaybeMultihash actualExisting, Optional<Long> actualSequence, MaybeMultihash claimedExisting) {
        super("CAS exception updating cryptree node. existing: " + actualExisting + ", claimed: " + claimedExisting);
        this.existing = actualExisting;
        this.claimedExisting = claimedExisting;
        this.sequence = actualSequence;
    }
}
