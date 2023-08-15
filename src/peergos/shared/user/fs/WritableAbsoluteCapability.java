package peergos.shared.user.fs;

import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.io.ipfs.bases.*;
import peergos.shared.storage.auth.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.stream.*;

public class WritableAbsoluteCapability extends AbsoluteCapability {

    public WritableAbsoluteCapability(PublicKeyHash owner, PublicKeyHash writer, byte[] mapKey, Optional<Bat> bat, SymmetricKey baseKey, SymmetricKey wBaseKey) {
        super(owner, writer, mapKey, bat, baseKey, Optional.of(wBaseKey));
    }

    public RelativeCapability relativise(AbsoluteCapability descendant) {
        if (! Objects.equals(owner, descendant.owner))
            throw new IllegalStateException("Files with different owners can't be descendant of each other!");

        Optional<SymmetricLink> writerLink = descendant.wBaseKey.flatMap(dWrite ->
                dWrite.equals(wBaseKey.get()) ?
                        Optional.empty() :
                        Optional.of(SymmetricLink.fromPair(wBaseKey.get(), dWrite)));
        Optional<PublicKeyHash> writerOpt = Objects.equals(writer, descendant.writer) ?
                Optional.empty() : Optional.of(descendant.writer);

        return new RelativeCapability(writerOpt, descendant.getMapKey(), descendant.bat, descendant.rBaseKey, writerLink);
    }

    @Override
    public WritableAbsoluteCapability withBaseKey(SymmetricKey newBaseKey) {
        return new WritableAbsoluteCapability(owner, writer, getMapKey(), bat, newBaseKey, wBaseKey.get());
    }

    public WritableAbsoluteCapability withBaseWriteKey(SymmetricKey newBaseWriteKey) {
        return new WritableAbsoluteCapability(owner, writer, getMapKey(), bat, rBaseKey, newBaseWriteKey);
    }

    @Override
    public WritableAbsoluteCapability withMapKey(byte[] newMapKey, Optional<Bat> newBat) {
        return new WritableAbsoluteCapability(owner, writer, newMapKey, newBat, rBaseKey, wBaseKey.get());
    }

    public AbsoluteCapability withOwner(PublicKeyHash owner) {
        return new WritableAbsoluteCapability(owner, writer, getMapKey(), bat, rBaseKey, wBaseKey.get());
    }

    public WritableAbsoluteCapability withSigner(PublicKeyHash newSigner) {
        return new WritableAbsoluteCapability(owner, newSigner, getMapKey(), bat, rBaseKey, wBaseKey.get());
    }

    /*  Return a capability link of the form #$owner/$writer/$mapkey+$bat/$baseKey/$baseWkey
     */
    public String toLink() {
        String encodedOwnerKey = Base58.encode(owner.serialize());
        String encodedWriterKey = Base58.encode(writer.serialize());
        String encodedMapKeyAndBat = Base58.encode(ArrayOps.concat(getMapKey(), bat.map(Bat::serialize).orElse(new byte[0])));
        String encodedBaseKey = Base58.encode(rBaseKey.serialize());
        String encodedWBaseKey = Base58.encode(wBaseKey.get().serialize());
        return Stream.of(encodedOwnerKey, encodedWriterKey, encodedMapKeyAndBat, encodedBaseKey, encodedWBaseKey)
                .collect(Collectors.joining("/", "#", ""));
    }

    @Override
    public String toString() {
        return writer + "." + ArrayOps.bytesToHex(getMapKey());
    }
}
