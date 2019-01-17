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

    public RelativeCapability relativise(WritableAbsoluteCapability descendant) {
        if (! Objects.equals(owner, descendant.owner))
            throw new IllegalStateException("Files with different owners can't be descendant of each other!");
        SymmetricLink writerLink = SymmetricLink.fromPair(wBaseKey.get(), descendant.wBaseKey.get());
        Optional<PublicKeyHash> writerOpt = Objects.equals(writer, descendant.writer) ?
                Optional.empty() : Optional.of(descendant.writer);

        return new RelativeCapability(writerOpt, descendant.getMapKey(), descendant.rBaseKey, Optional.of(writerLink));
    }

    @Override
    public WritableAbsoluteCapability withBaseKey(SymmetricKey newBaseKey) {
        return new WritableAbsoluteCapability(owner, writer, getMapKey(), newBaseKey, wBaseKey.get());
    }
}
