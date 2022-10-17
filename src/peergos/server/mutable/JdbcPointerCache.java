package peergos.server.mutable;

import peergos.server.corenode.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.mutable.*;
import peergos.shared.storage.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;

public class JdbcPointerCache implements PointerCache {

    private final JdbcIpnsAndSocial store;
    private final ContentAddressedStorage storage;

    public JdbcPointerCache(JdbcIpnsAndSocial store, ContentAddressedStorage storage) {
        this.store = store;
        this.storage = storage;
    }

    @Override
    public synchronized CompletableFuture<Boolean> put(PublicKeyHash owner, PublicKeyHash writer, byte[] writerSignedBtreeRootHash) {
        return store.getPointer(writer)
                .thenCompose(current -> {
                    if (current.isPresent() && Arrays.equals(current.get(), writerSignedBtreeRootHash))
                        return Futures.of(true);
                    Optional<PointerUpdate> currentVal = current.map(CborObject::fromByteArray).map(PointerUpdate::fromCbor);

                    return storage.getSigningKey(writer).thenCompose(signerOpt -> {
                        if (signerOpt.isEmpty())
                            throw new IllegalStateException("Couldn't retrieve signing key!");
                        PointerUpdate newVal = PointerUpdate.fromCbor(CborObject.fromByteArray(signerOpt.get().unsignMessage(writerSignedBtreeRootHash)));

                        if (currentVal.isPresent() && currentVal.get().sequence.isPresent()) {
                            long currentSeq = currentVal.get().sequence.get();
                            if (newVal.sequence.isEmpty() || newVal.sequence.get() < currentSeq)
                                throw new IllegalStateException("Invalid pointer update! Sequence number must increase.");
                        }
                        return store.setPointer(writer, current, writerSignedBtreeRootHash);
                    });
                });
    }

    @Override
    public CompletableFuture<Optional<byte[]>> get(PublicKeyHash owner, PublicKeyHash writer) {
        return store.getPointer(writer);
    }
}
