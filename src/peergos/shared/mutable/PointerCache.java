package peergos.shared.mutable;

import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;

import java.util.*;
import java.util.concurrent.*;

public interface PointerCache {

    CompletableFuture<Boolean> put(PublicKeyHash owner, PublicKeyHash writer, byte[] writerSignedBtreeRootHash);

    CompletableFuture<Optional<byte[]>> get(PublicKeyHash owner, PublicKeyHash writer);

    default boolean doUpdate(Optional<byte[]> current, byte[] update, PublicSigningKey signer) {
        if (current.isPresent() && Arrays.equals(current.get(), update))
            return false;
        Optional<PointerUpdate> currentVal = current.map(signer::unsignMessage)
                .map(CborObject::fromByteArray)
                .map(PointerUpdate::fromCbor);

        PointerUpdate newVal = PointerUpdate.fromCbor(CborObject.fromByteArray(signer.unsignMessage(update)));

        if (currentVal.isPresent() && currentVal.get().sequence.isPresent()) {
            long currentSeq = currentVal.get().sequence.get();
            if (newVal.sequence.isEmpty() || newVal.sequence.get() < currentSeq)
                throw new IllegalStateException("Invalid pointer update! Sequence number must increase.");
        }
        return true;
    }
}
