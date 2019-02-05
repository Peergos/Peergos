package peergos.shared.user.fs;

import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.symmetric.*;

import java.util.*;

public class WritableAbsoluteCapability extends AbsoluteCapability {

    public WritableAbsoluteCapability(PublicKeyHash owner, PublicKeyHash writer, byte[] mapKey, SymmetricKey baseKey, SymmetricKey wBaseKey) {
        super(owner, writer, mapKey, baseKey, Optional.of(wBaseKey));
    }

    public RelativeCapability relativise(AbsoluteCapability descendant) {
        if (! Objects.equals(owner, descendant.owner))
            throw new IllegalStateException("Files with different owners can't be descendant of each other!");

        Optional<SymmetricLink> writerLink = descendant.wBaseKey.map(dWrite -> SymmetricLink.fromPair(wBaseKey.get(), dWrite));
        Optional<PublicKeyHash> writerOpt = Objects.equals(writer, descendant.writer) ?
                Optional.empty() : Optional.of(descendant.writer);

        return new RelativeCapability(writerOpt, descendant.getMapKey(), descendant.rBaseKey, writerLink);
    }

    @Override
    public WritableAbsoluteCapability withBaseKey(SymmetricKey newBaseKey) {
        return new WritableAbsoluteCapability(owner, writer, getMapKey(), newBaseKey, wBaseKey.get());
    }

    public WritableAbsoluteCapability withBaseWriteKey(SymmetricKey newBaseWriteKey) {
        return new WritableAbsoluteCapability(owner, writer, getMapKey(), rBaseKey, newBaseWriteKey);
    }

    @Override
    public WritableAbsoluteCapability withMapKey(byte[] newMapKey) {
        return new WritableAbsoluteCapability(owner, writer, newMapKey, rBaseKey, wBaseKey.get());
    }

    public WritableAbsoluteCapability withSigner(PublicKeyHash newSigner) {
        return new WritableAbsoluteCapability(owner, newSigner, getMapKey(), rBaseKey, wBaseKey.get());
    }
}
