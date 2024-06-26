package peergos.shared.mutable;

import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;

public interface PointerCache {

    CompletableFuture<Boolean> put(PublicKeyHash owner, PublicKeyHash writer, byte[] writerSignedBtreeRootHash);

    CompletableFuture<Optional<byte[]>> get(PublicKeyHash owner, PublicKeyHash writer);

    default CompletableFuture<Boolean> doUpdate(Optional<byte[]> current, byte[] update, PublicSigningKey signer) {
        if (current.isPresent() && Arrays.equals(current.get(), update))
            return Futures.of(false);
        Optional<CompletableFuture<Optional<PointerUpdate>>> currentFut = current.map(signer::unsignMessage)
                .map(f -> f.thenApply(CborObject::fromByteArray))
                .map(f -> f.thenApply(PointerUpdate::fromCbor))
                .map(f -> f.thenApply(Optional::of));

        return signer.unsignMessage(update).thenCompose(unsigned -> {
            PointerUpdate newVal = PointerUpdate.fromCbor(CborObject.fromByteArray(unsigned));

            return currentFut.orElse(Futures.of(Optional.empty())).thenApply(currentVal -> {
                if (currentVal.isPresent() && currentVal.get().sequence.isPresent()) {
                    long currentSeq = currentVal.get().sequence.get();
                    if (newVal.sequence.isEmpty() || newVal.sequence.get() < currentSeq)
                        throw new IllegalStateException("Invalid pointer update! Sequence number must increase.");
                }
                return true;
            });
        });
    }
}
