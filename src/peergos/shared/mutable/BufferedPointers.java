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
import java.util.stream.*;

public class BufferedPointers implements MutablePointers {

    public static class SignedPointerUpdate {
        public final PublicKeyHash owner, writer;
        public final byte[] signedUpdate;

        public SignedPointerUpdate(PublicKeyHash owner, PublicKeyHash writer, byte[] signedUpdate) {
            this.owner = owner;
            this.writer = writer;
            this.signedUpdate = signedUpdate;
        }
    }

    private final MutablePointers target;
    private final Map<PublicKeyHash, SignedPointerUpdate> buffer = new HashMap<>();
    private final List<SignedPointerUpdate> order = new ArrayList<>();

    public BufferedPointers(MutablePointers target) {
        this.target = target;
    }

    public boolean isEmpty() {
        return order.isEmpty();
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getPointer(PublicKeyHash owner, PublicKeyHash writer) {
        synchronized (buffer) {
            SignedPointerUpdate buffered = buffer.get(writer);
            if (buffered != null)
                return CompletableFuture.completedFuture(Optional.of(buffered.signedUpdate));
        }
        return target.getPointer(owner, writer);
    }

    @Override
    public CompletableFuture<Boolean> setPointer(PublicKeyHash owner, PublicKeyHash writer, byte[] writerSignedBtreeRootHash) {
        synchronized (buffer) {
            SignedPointerUpdate update = new SignedPointerUpdate(owner, writer, writerSignedBtreeRootHash);
            buffer.put(writer, update);
            order.add(update);
        }
        return Futures.of(true);
    }

    /**
     *  Merge updates to a single pointer into a single update
     */
    public void condense(Map<PublicKeyHash, SigningPrivateKeyAndPublicHash> writers) {
        int start = 0;
        List<SignedPointerUpdate> newOrder = new ArrayList<>();
        int j=1;
        for (; j < order.size(); j++) {
            SignedPointerUpdate first = order.get(start);
            // preserve order of inter writer commits
            if (! order.get(j).writer.equals(first.writer)) {
                if (j - start > 1) {
                    PointerUpdate firstUpdate = parse(first.signedUpdate);
                    MaybeMultihash original = firstUpdate.original;
                    MaybeMultihash updated = parse(order.get(j - 1).signedUpdate).updated;
                    newOrder.add(new SignedPointerUpdate(first.owner, first.writer, writers.get(first.writer).secret.signMessage(new peergos.shared.mutable.PointerUpdate(original, updated, firstUpdate.sequence).serialize())));
                } else
                    newOrder.add(first); // nothing to condense
                start = j;
            }
        }
        if (j - start > 0) {// condense the last run
            SignedPointerUpdate first = order.get(start);
            PointerUpdate firstUpdate = parse(first.signedUpdate);
            MaybeMultihash original = firstUpdate.original;
            MaybeMultihash updated = parse(order.get(j - 1).signedUpdate).updated;
            newOrder.add(new SignedPointerUpdate(first.owner, first.writer, writers.get(first.writer).secret.signMessage(new peergos.shared.mutable.PointerUpdate(original, updated, firstUpdate.sequence).serialize())));
        }
        order.clear();
        order.addAll(newOrder);
    }

    private static PointerUpdate parse(byte[] signedCas) {
        return PointerUpdate.fromCbor(CborObject.fromByteArray(Arrays.copyOfRange(signedCas, TweetNaCl.SIGNATURE_SIZE_BYTES, signedCas.length)));
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
