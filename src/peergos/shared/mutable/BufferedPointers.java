package peergos.shared.mutable;

import peergos.shared.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.storage.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

public class BufferedPointers implements MutablePointers {

    public static class WriterUpdate {
        public final PublicKeyHash writer;
        public final MaybeMultihash prevHash;
        public final MaybeMultihash currentHash;
        public final Optional<Long> currentSequence;

        public WriterUpdate(PublicKeyHash writer, MaybeMultihash prevHash, MaybeMultihash currentHash, Optional<Long> currentSequence) {
            this.writer = writer;
            this.prevHash = prevHash;
            this.currentHash = currentHash;
            this.currentSequence = currentSequence;
        }

        @Override
        public String toString() {
            return writer + ":" + prevHash + " => " + currentSequence + currentHash;
        }
    }

    private final MutablePointers target;
    private final Map<PublicKeyHash, SigningPrivateKeyAndPublicHash> writers = new HashMap<>();
    private final Map<PublicKeyHash, WriterUpdate> latest = new HashMap<>();
    private final Map<PublicKeyHash, WriterUpdate> lastCommits = new LRUCache<>(20);
    private final List<WriterUpdate> writerUpdates = new ArrayList<>();

    public BufferedPointers(MutablePointers target) {
        if (target instanceof BufferedPointers)
            throw new IllegalStateException("Nested BufferedPointers!");
        this.target = target;
    }

    public Optional<Pair<Optional<Cid>, Optional<Long>>> getCommittedPointerTarget(PublicKeyHash writer) {
        synchronized (writerUpdates) {
            if (writerUpdates.isEmpty())
                return Optional.ofNullable(lastCommits.get(writer))
                        .map(w -> new Pair<>(w.currentHash.toOptional().map(h -> (Cid) h),
                                w.currentSequence));
            return writerUpdates.stream()
                    .filter(u -> u.writer.equals(writer))
                    .findFirst()
                    .map(m -> new Pair<>(m.prevHash.toOptional().map(h -> (Cid) h),
                            m.currentSequence.map(x -> x - 1)));
        }
    }

    public List<WriterUpdate> getUpdates() {
        synchronized (writerUpdates) {
            return writerUpdates.subList(0, writerUpdates.size());
        }
    }

    public Map<PublicKeyHash, SigningPrivateKeyAndPublicHash> getSigners() {
        return writers;
    }

    public PointerUpdate addWrite(SigningPrivateKeyAndPublicHash w,
                                  MaybeMultihash newHash,
                                  MaybeMultihash prevHash,
                                  Optional<Long> prevSequence) {
        synchronized (writerUpdates) {
            if (Objects.equals(prevHash, newHash) &&
                    (writerUpdates.isEmpty() || !writerUpdates.get(writerUpdates.size() - 1).currentHash.equals(newHash)))
                throw new IllegalStateException("Noop pointer update!");
            PublicKeyHash writer = w.publicKeyHash;
            writers.put(writer, w);
            if (writerUpdates.isEmpty()) {
                writerUpdates.add(new WriterUpdate(writer, prevHash, newHash, PointerUpdate.increment(prevSequence)));
            } else {
                WriterUpdate last = writerUpdates.get(writerUpdates.size() - 1);
                if (last.writer.equals(writer)) {
                    writerUpdates.set(writerUpdates.size() - 1, new WriterUpdate(writer, last.prevHash, newHash, last.currentSequence));
                } else {
                    writerUpdates.add(new WriterUpdate(writer, prevHash, newHash, PointerUpdate.increment(prevSequence)));
                }
            }
            WriterUpdate last = writerUpdates.get(writerUpdates.size() - 1);
            latest.put(w.publicKeyHash, last);
            return new PointerUpdate(last.prevHash, last.currentHash, last.currentSequence);
        }
    }

    public boolean isEmpty() {
        synchronized (writerUpdates) {
            return writerUpdates.isEmpty();
        }
    }

    @Override
    public CompletableFuture<PointerUpdate> getPointerTarget(PublicKeyHash owner, PublicKeyHash writer, ContentAddressedStorage ipfs) {
        WriterUpdate cached = latest.get(writer);
        if (cached != null) {
            return Futures.of(new PointerUpdate(cached.prevHash, cached.currentHash, cached.currentSequence));
        }
        return target.getPointerTarget(owner, writer, ipfs);
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getPointer(PublicKeyHash owner, PublicKeyHash writer) {
        throw new IllegalStateException("Shouldn't get here!");
    }

    @Override
    public CompletableFuture<Boolean> setPointer(PublicKeyHash owner, SigningPrivateKeyAndPublicHash writer, PointerUpdate casUpdate) {
        addWrite(writer, casUpdate.updated, casUpdate.original, casUpdate.sequence);
        return Futures.of(true);
    }

    @Override
    public CompletableFuture<Boolean> setPointer(PublicKeyHash owner, PublicKeyHash writer, byte[] writerSignedBtreeRootHash) {
        throw new IllegalStateException("Shouldn't get here!");
    }

    public List<Cid> getRoots() {
        synchronized (writerUpdates) {
            return writerUpdates.stream()
                    .flatMap(u -> u.currentHash.toOptional().stream())
                    .map(c -> (Cid) c)
                    .collect(Collectors.toList());
        }
    }

    public CompletableFuture<Boolean> commit(PublicKeyHash owner,
                                             SigningPrivateKeyAndPublicHash signer,
                                             PointerUpdate casUpdate) {
        return signer.secret.signMessage(casUpdate.serialize())
                .thenCompose(signed -> target.setPointer(owner, signer.publicKeyHash, signed))
                .thenApply(b -> {
                    if (b)
                        lastCommits.put(signer.publicKeyHash, new WriterUpdate(signer.publicKeyHash, casUpdate.original, casUpdate.updated, casUpdate.sequence));
                    return b;
                });
    }

    @Override
    public MutablePointers clearCache() {
        return new BufferedPointers(target.clearCache());
    }

    public void clear() {
        writers.clear();
        synchronized (writerUpdates) {
            writerUpdates.clear();
        }
        latest.clear();
    }
}
