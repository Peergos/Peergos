package peergos.shared.mutable;

import peergos.server.crypto.*;
import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.cid.*;
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

    /**
     *  Merge updates to a single pointer into a single update
     */
    public void condense(Map<PublicKeyHash, SigningPrivateKeyAndPublicHash> writers) {
        int start = 0;
        List<PointerUpdate> newOrder = new ArrayList<>();
        int j=1;
        for (; j < order.size(); j++) {
            PointerUpdate first = order.get(start);
            // preserve order of inter writer commits
            if (! order.get(j).writer.equals(first.writer)) {
                if (j - start > 1) {
                    MaybeMultihash original = parse(first.signedUpdate).original;
                    MaybeMultihash updated = parse(order.get(j - 1).signedUpdate).updated;
                    newOrder.add(new PointerUpdate(first.owner, first.writer, writers.get(first.writer).secret.signMessage(new HashCasPair(original, updated).serialize())));
                } else
                    newOrder.add(first); // nothing to condense
                start = j;
            }
        }
        if (j - start > 0) {// condense the last run
            PointerUpdate first = order.get(start);
            MaybeMultihash original = parse(first.signedUpdate).original;
            MaybeMultihash updated = parse(order.get(j - 1).signedUpdate).updated;
            newOrder.add(new PointerUpdate(first.owner, first.writer, writers.get(first.writer).secret.signMessage(new HashCasPair(original, updated).serialize())));
        }
        order.clear();
        order.addAll(newOrder);
    }

    private static HashCasPair parse(byte[] signedCas) {
        return HashCasPair.fromCbor(CborObject.fromByteArray(Arrays.copyOfRange(signedCas, TweetNaCl.SIGNATURE_SIZE_BYTES, signedCas.length)));
    }

    public List<Cid> getRoots() {
        return order.stream()
                .map(u -> parse(u.signedUpdate))
                .map(p -> p.updated)
                .flatMap(m -> m.toOptional().stream())
                .map(c -> (Cid)c)
                .collect(Collectors.toList());
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
