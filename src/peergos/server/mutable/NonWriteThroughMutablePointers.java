package peergos.server.mutable;

import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.mutable.*;
import peergos.shared.storage.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

public class NonWriteThroughMutablePointers implements MutablePointers {

    private final MutablePointers source;
    private final ContentAddressedStorage storage;
    private final Map<PublicKeyHash, byte[]> modifications;

    public NonWriteThroughMutablePointers(MutablePointers source, ContentAddressedStorage storage) {
        this.source = source;
        this.storage = storage;
        this.modifications = new HashMap<>();
    }

    @Override
    public CompletableFuture<Boolean> setPointer(PublicKeyHash owner, PublicKeyHash writer, byte[] writerSignedBtreeRootHash) {
        try {
            if (! modifications.containsKey(writer)) {
                Optional<byte[]> existing = source.getPointer(owner, writer).get();
                existing.map(val -> modifications.put(writer, val));
            }
            Optional<PublicSigningKey> opt = storage.getSigningKey(owner, writer).get();
            if (! opt.isPresent())
                throw new IllegalStateException("Couldn't retrieve signing key!");
            return MutablePointers.isValidUpdate(opt.get(), Optional.ofNullable(modifications.get(writer)), writerSignedBtreeRootHash)
                    .thenApply(x -> {
                        modifications.put(writer, writerSignedBtreeRootHash);
                        return true;
                    });
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getPointer(PublicKeyHash owner, PublicKeyHash writer) {
        try {
            if (modifications.containsKey(writer))
                return CompletableFuture.completedFuture(Optional.of(modifications.get(writer)));
            return source.getPointer(owner, writer);
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public CompletableFuture<Boolean> setPointers(PublicKeyHash owner, List<SignedPointerUpdate> updates) {
        // Validate all updates first (all-or-nothing), then apply
        try {
            for (SignedPointerUpdate u : updates) {
                if (!modifications.containsKey(u.writer)) {
                    Optional<byte[]> existing = source.getPointer(owner, u.writer).get();
                    existing.ifPresent(val -> modifications.put(u.writer, val));
                }
                Optional<PublicSigningKey> opt = storage.getSigningKey(owner, u.writer).get();
                if (!opt.isPresent())
                    throw new IllegalStateException("Couldn't retrieve signing key!");
                MutablePointers.isValidUpdate(opt.get(), Optional.ofNullable(modifications.get(u.writer)), u.signed).get();
            }
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
        updates.forEach(u -> modifications.put(u.writer, u.signed));
        return Futures.of(true);
    }

    @Override
    public MutablePointers clearCache() {
        return this;
    }
}
