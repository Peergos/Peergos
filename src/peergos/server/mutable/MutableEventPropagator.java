package peergos.server.mutable;

import peergos.shared.crypto.hash.*;
import peergos.shared.mutable.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

public class MutableEventPropagator implements MutablePointers {

    private final MutablePointers target;
    private final List<Consumer<? super MutableEvent>> listeners = new ArrayList<>();

    public MutableEventPropagator(MutablePointers target) {
        this.target = target;
    }

    public void addListener(Consumer<? super MutableEvent> listener) {
        listeners.add(listener);
    }

    @Override
    public CompletableFuture<Boolean> setPointer(PublicKeyHash owner, PublicKeyHash writer, byte[] writerSignedBtreeRootHash) {
        return target.setPointer(owner, writer, writerSignedBtreeRootHash)
                .thenApply(res -> {
                    if (res) {
                        MutableEvent event = new MutableEvent(owner, writer, writerSignedBtreeRootHash);
                        for (Consumer<? super MutableEvent> listener : listeners) {
                            listener.accept(event);
                        }
                    }
                    return res;
                });
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getPointer(PublicKeyHash owner, PublicKeyHash writer) {
        return target.getPointer(owner, writer);
    }

    @Override
    public MutablePointers clearCache() {
        return this;
    }
}
