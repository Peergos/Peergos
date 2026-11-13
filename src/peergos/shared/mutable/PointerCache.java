package peergos.shared.mutable;

import peergos.shared.storage.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;

public interface PointerCache {

    CompletableFuture<Boolean> put(PublicKeyHash owner, PublicKeyHash writer, byte[] writerSignedBtreeRootHash);

    CompletableFuture<Optional<byte[]>> get(PublicKeyHash owner, PublicKeyHash writer);

    default CompletableFuture<Boolean> doUpdate(PublicKeyHash owner,
                                                PublicKeyHash writer,
                                                Optional<byte[]> current,
                                                byte[] update,
                                                ContentAddressedStorage storage) {
        if (current.isPresent() && Arrays.equals(current.get(), update))
            return Futures.of(false);
        CompletableFuture<Optional<Pair<PublicSigningKey, PointerUpdate>>> currentFut = current
                .map(c -> PointerKeys.pointerKey(owner, writer, c, storage)
                        .thenCompose(k -> PointerKeys.decode(k, c).thenApply(u -> Optional.of(new Pair<>(k, u)))))
                .orElse(Futures.of(Optional.empty()));

        return currentFut.thenCompose(currentVal -> PointerKeys.pointerKey(owner, writer, update, storage)
                .thenCompose(key -> PointerKeys.decode(key, update).thenApply(newVal -> {
                    PointerKeys.checkTransition(writer, currentVal.map(p -> p.left), key);
                    if (currentVal.isPresent() && currentVal.get().right.sequence.isPresent()) {
                        long currentSeq = currentVal.get().right.sequence.get();
                        if (newVal.sequence.isEmpty() || newVal.sequence.get() < currentSeq)
                            throw new IllegalStateException("Invalid pointer update! Sequence number must increase.");
                    }
                    return true;
                })));
    }
}
