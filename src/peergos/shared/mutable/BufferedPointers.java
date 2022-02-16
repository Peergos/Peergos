package peergos.shared.mutable;

import peergos.shared.crypto.hash.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

public class BufferedPointers implements MutablePointers {

    public static class PointerUpdate {
        public final PublicKeyHash owner, writer;
        public final byte[] signedUpdate;

        public PointerUpdate(PublicKeyHash owner, PublicKeyHash writer, byte[] signedUpdate) {
            this.owner = owner;
            this.writer = writer;
            this.signedUpdate = signedUpdate;
        }
    }

    private final MutablePointers target;
    private final Map<PublicKeyHash, PointerUpdate> buffer = new HashMap<>();
    private final List<PointerUpdate> order = new ArrayList<>();
    private Supplier<CompletableFuture<Boolean>> watcher;

    public BufferedPointers(MutablePointers target) {
        this.target = target;
    }

    public void watchUpdates(Supplier<CompletableFuture<Boolean>> watcher) {
        this.watcher = watcher;
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getPointer(PublicKeyHash owner, PublicKeyHash writer) {
        synchronized (buffer) {
            PointerUpdate buffered = buffer.get(writer);
            if (buffered != null)
                return CompletableFuture.completedFuture(Optional.of(buffered.signedUpdate));
        }
        return target.getPointer(owner, writer);
    }

    @Override
    public CompletableFuture<Boolean> setPointer(PublicKeyHash owner, PublicKeyHash writer, byte[] writerSignedBtreeRootHash) {
        synchronized (buffer) {
            PointerUpdate update = new PointerUpdate(owner, writer, writerSignedBtreeRootHash);
            buffer.put(writer, update);
            order.add(update);
        }
        return watcher.get();
    }

    public CompletableFuture<List<Boolean>> commit() {
        return Futures.combineAllInOrder(order.stream()
                .map(u -> target.setPointer(u.owner, u.writer, u.signedUpdate))
                .collect(Collectors.toList()));
    }

    public void clear() {
        buffer.clear();
        order.clear();
    }
}
