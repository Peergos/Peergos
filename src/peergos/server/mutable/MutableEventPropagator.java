package peergos.server.mutable;

import peergos.shared.crypto.hash.*;
import peergos.shared.mutable.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

public class MutableEventPropagator implements MutablePointers {

    private final MutablePointers target;
    private final List<Consumer<MutableEvent>> listeners = new ArrayList<>();

    public MutableEventPropagator(MutablePointers target) {
        this.target = target;
    }

    public void addListener(Consumer<MutableEvent> listener) {
        listeners.add(listener);
    }

    @Override
    public CompletableFuture<Boolean> setPointer(PublicKeyHash owner, PublicKeyHash writer, byte[] writerSignedBtreeRootHash) {
        return target.setPointer(owner, writer, writerSignedBtreeRootHash)
                .thenApply(res -> {
                    if (res) {
                        MutableEvent event = new MutableEvent(writer, writerSignedBtreeRootHash);
                        for (Consumer<MutableEvent> listener : listeners) {
                            listener.accept(event);
                        }
                    }
                    return res;
                });
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getPointer(PublicKeyHash writer) {
        return target.getPointer(writer);
    }
}
