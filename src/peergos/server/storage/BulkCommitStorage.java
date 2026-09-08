package peergos.server.storage;

import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.storage.auth.*;
import peergos.shared.user.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.mutable.*;
import peergos.shared.storage.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

/** Applies a whole logical write - blocks and pointer updates - for an owner whose home server we are.
 *
 *  This is the local half of {@link ContentAddressedStorage#bulkCommit}; the proxying storage above it
 *  forwards the commit intact to the owner's server when that isn't us.
 *
 *  The blocks carry no signature of their own. What authorises them is that the pointer update signs a
 *  root which names them, transitively, by hash - so this class will not write a block until it has
 *  checked that the signed root really does reach it, and that the commit leaves no dangling link.
 */
public class BulkCommitStorage extends DelegatingStorage {

    private final ContentAddressedStorage target;
    private final MutablePointers pointers;
    private final Hasher hasher;
    private final BiFunction<PublicKeyHash, PublicKeyHash, Boolean> registerWriter;

    public BulkCommitStorage(ContentAddressedStorage target,
                             MutablePointers pointers,
                             Hasher hasher,
                             BiFunction<PublicKeyHash, PublicKeyHash, Boolean> registerWriter) {
        super(target);
        this.target = target;
        this.pointers = pointers;
        this.hasher = hasher;
        this.registerWriter = registerWriter;
    }

    @Override
    public ContentAddressedStorage directToOrigin() {
        return this;
    }

    @Override
    public CompletableFuture<List<Cid>> bulkCommit(PublicKeyHash owner, BulkCommit commit) {
        for (WriterCommit w : commit.writers) {
            if (w.pointer.isEmpty()) {
                // Nothing signs a root that names these blocks yet, so the writer signs the list itself,
                // and they stay unreachable until a later call's pointer update makes them not.
                if (w.blockListSignature.isEmpty())
                    throw new IllegalStateException("A bulk commit with no pointer update is unauthenticated!");
                if (commit.tid.isEmpty())
                    throw new IllegalStateException("Blocks committed before the pointer that names them need a transaction!");
            }
            if (Stream.concat(w.cborBlocks.stream(), w.rawBlocks.stream())
                    .anyMatch(b -> b.length > ContentAddressedStorage.MAX_BLOCK_SIZE))
                throw new IllegalStateException("Block too big!");
        }
        return hashBlocks(commit)
                .thenCompose(hashes -> updates(owner, commit)
                        .thenCompose(updates -> verifyBlockLists(owner, commit, hashes)
                                .thenCompose(x -> verify(owner, commit, hashes, updates))
                                .thenCompose(inCall -> registerNewWriters(owner, commit, updates, inCall)))
                        .thenCompose(x -> withTransaction(owner, commit.tid,
                                tid -> writeBlocks(owner, commit, tid))
                                .thenCompose(written -> {
                                    List<SignedPointerUpdate> signed = commit.writers.stream()
                                            .flatMap(w -> w.pointer.stream())
                                            .collect(Collectors.toList());
                                    if (signed.isEmpty())
                                        return Futures.of(written);
                                    return pointers.setPointers(owner, signed).thenApply(b -> written);
                                })));
    }

    /** The hash of every block travelling inline, in commit order: each writer's cbor blocks then its raw ones. */
    private CompletableFuture<List<List<Cid>>> hashBlocks(BulkCommit commit) {
        return Futures.combineAllInOrder(commit.writers.stream()
                .map(w -> Futures.combineAllInOrder(Stream.concat(
                                w.cborBlocks.stream().map(b -> hasher.hash(b, false)),
                                w.rawBlocks.stream().map(b -> hasher.hash(b, true)))
                        .collect(Collectors.toList())))
                .collect(Collectors.toList()));
    }

    /** Check that the signed roots reach every block in the call, and that the call leaves no link dangling.
     *
     * @return the cbor blocks in the call, by hash
     */
    private CompletableFuture<Map<Cid, byte[]>> verify(PublicKeyHash owner,
                                                       BulkCommit commit,
                                                       List<List<Cid>> hashes,
                                                       List<Optional<PointerUpdate>> updates) {
        Map<Cid, byte[]> cborInCall = new HashMap<>();
        Set<Cid> mustBeReachable = new HashSet<>();
        Set<Cid> declared = new HashSet<>();
        for (int i = 0; i < commit.writers.size(); i++) {
            WriterCommit w = commit.writers.get(i);
            List<Cid> cids = hashes.get(i);
            for (int j = 0; j < w.cborBlocks.size(); j++)
                cborInCall.put(cids.get(j), w.cborBlocks.get(j));
            // Blocks sent ahead of the pointer that will name them have nothing to be reachable from yet
            if (w.pointer.isPresent())
                mustBeReachable.addAll(cids);
            declared.addAll(cids);
            declared.addAll(w.preWritten);
        }

        List<Cid> roots = updates.stream()
                .flatMap(Optional::stream)
                .flatMap(u -> u.updated.toOptional().stream())
                .map(h -> (Cid) h)
                .collect(Collectors.toList());
        Set<Cid> external = new HashSet<>(commit.writers.stream()
                .flatMap(w -> w.preWritten.stream())
                .collect(Collectors.toSet()));

        // Every block in the call must be reachable from a root this call signs
        Set<Cid> reachable = new HashSet<>();
        for (Cid root : roots) {
            if (cborInCall.containsKey(root))
                markReachable(root, reachable, cborInCall);
            else
                external.add(root);
        }
        Optional<Cid> orphan = mustBeReachable.stream()
                .filter(c -> ! reachable.contains(c))
                .findFirst();
        if (orphan.isPresent())
            throw new IllegalStateException("Block in a bulk commit is not reachable from the new root: " + orphan.get());

        // Every link out of a block this call is making reachable must resolve, here or in what we hold
        for (Map.Entry<Cid, byte[]> e : cborInCall.entrySet()) {
            if (! mustBeReachable.contains(e.getKey()))
                continue;
            for (Multihash link : CborObject.fromByteArray(e.getValue()).links()) {
                Cid c = (Cid) link;
                if (c.isIdentity() || declared.contains(c))
                    continue;
                external.add(c);
            }
        }

        return Futures.combineAllInOrder(external.stream()
                        .map(c -> target.getSize(owner, c)
                                .thenApply(size -> new Pair<>(c, size.isPresent())))
                        .collect(Collectors.toList()))
                .thenApply(results -> {
                    Optional<Cid> missing = results.stream()
                            .filter(p -> ! p.right)
                            .map(p -> p.left)
                            .findFirst();
                    if (missing.isPresent())
                        throw new IllegalStateException("Bulk commit references a block we don't have: " + missing.get());
                    return cborInCall;
                });
    }

    /** A writer with no previous pointer value is being created by this commit, and the server won't accept
     *  its blocks until it knows the owner owns it. The proof is in the commit: an existing writer's newly
     *  signed WriterData names it as an owned key.
     */
    private CompletableFuture<Boolean> registerNewWriters(PublicKeyHash owner,
                                                          BulkCommit commit,
                                                          List<Optional<PointerUpdate>> updates,
                                                          Map<Cid, byte[]> cborInCall) {
        List<PublicKeyHash> newWriters = new ArrayList<>();
        for (int i = 0; i < commit.writers.size(); i++)
            if (updates.get(i).map(u -> ! u.original.isPresent()).orElse(false))
                newWriters.add(commit.writers.get(i).writer);
        if (newWriters.isEmpty())
            return Futures.of(true);

        ContentAddressedStorage withCallBlocks = overlay(cborInCall);
        // Only a writer that isn't itself being created here can vouch for one that is, so widen the set
        // of vouchers a step at a time until it stops growing.
        Set<PublicKeyHash> authorised = new HashSet<>();
        authorised.add(owner);
        for (int i = 0; i < commit.writers.size(); i++)
            if (updates.get(i).map(u -> u.original.isPresent()).orElse(true))
                authorised.add(commit.writers.get(i).writer);
        List<PublicKeyHash> remaining = new ArrayList<>(newWriters);
        while (! remaining.isEmpty()) {
            Set<PublicKeyHash> owned = new HashSet<>();
            for (int i = 0; i < commit.writers.size(); i++) {
                WriterCommit w = commit.writers.get(i);
                if (! authorised.contains(w.writer))
                    continue;
                updates.get(i).flatMap(u -> u.updated.toOptional()).ifPresent(root -> owned.addAll(
                        ContentAddressedStorage.getWriterData(owner, (Cid) root, Optional.empty(), withCallBlocks)
                                .thenCompose(cwd -> cwd.props.get().directOwnedKeys(owner, withCallBlocks, hasher))
                                .join()));
            }
            List<PublicKeyHash> vouched = remaining.stream()
                    .filter(owned::contains)
                    .collect(Collectors.toList());
            if (vouched.isEmpty())
                throw new IllegalStateException("Bulk commit creates a writer that nothing in it owns: " + remaining.get(0));
            authorised.addAll(vouched);
            remaining.removeAll(vouched);
        }
        for (PublicKeyHash newWriter : newWriters) {
            if (! registerWriter.apply(owner, newWriter))
                throw new IllegalStateException("Key not allowed to write to this server: " + newWriter);
        }
        return Futures.of(true);
    }

    /** A view of the store with this call's blocks in it, for reading a graph the call is only proposing. */
    private ContentAddressedStorage overlay(Map<Cid, byte[]> cborInCall) {
        ContentAddressedStorage delegate = target;
        return new DelegatingStorage(delegate) {
            @Override
            public ContentAddressedStorage directToOrigin() {
                return this;
            }

            @Override
            public CompletableFuture<Optional<byte[]>> getRaw(PublicKeyHash owner, Cid hash, Optional<BatWithId> bat) {
                byte[] block = cborInCall.get(hash);
                if (block != null)
                    return Futures.of(Optional.of(block));
                return delegate.getRaw(owner, hash, bat);
            }

            @Override
            public CompletableFuture<Optional<CborObject>> get(PublicKeyHash owner, Cid hash, Optional<BatWithId> bat) {
                if (hash.isIdentity())
                    return Futures.of(Optional.of(CborObject.fromByteArray(hash.getHash())));
                return getRaw(owner, hash, bat).thenApply(opt -> opt.map(CborObject::fromByteArray));
            }
        };
    }

    /** The pointer update each writer is proposing, which also checks the signature we are relying on. */
    private CompletableFuture<List<Optional<PointerUpdate>>> updates(PublicKeyHash owner, BulkCommit commit) {
        return Futures.combineAllInOrder(commit.writers.stream()
                .map(w -> w.pointer.isEmpty() ?
                        Futures.of(Optional.<PointerUpdate>empty()) :
                        writerKey(owner, w.writer)
                                .thenCompose(key -> key.unsignMessage(w.pointer.get().signed))
                                .thenApply(signed -> Optional.of(PointerUpdate.fromCbor(CborObject.fromByteArray(signed)))))
                .collect(Collectors.toList()));
    }

    private CompletableFuture<PublicSigningKey> writerKey(PublicKeyHash owner, PublicKeyHash writer) {
        return target.getSigningKey(owner, writer)
                .thenApply(keyOpt -> {
                    if (keyOpt.isEmpty())
                        throw new IllegalStateException("Couldn't retrieve writer key " + writer);
                    return keyOpt.get();
                });
    }

    /** A writer sending blocks with no pointer update signs the ordered list of their hashes, bound to
     *  the sequence its pointer is heading for so the list can't be replayed against a later state.
     */
    private CompletableFuture<Boolean> verifyBlockLists(PublicKeyHash owner, BulkCommit commit, List<List<Cid>> hashes) {
        List<Integer> blocksOnly = IntStream.range(0, commit.writers.size())
                .filter(i -> commit.writers.get(i).pointer.isEmpty())
                .boxed()
                .collect(Collectors.toList());
        if (blocksOnly.isEmpty())
            return Futures.of(true);
        return Futures.combineAllInOrder(blocksOnly.stream()
                        .map(i -> {
                            WriterCommit w = commit.writers.get(i);
                            return pointers.getPointerTarget(owner, w.writer, target)
                                    .thenCompose(current -> WriterCommit.blockListPayload(hashes.get(i),
                                            PointerUpdate.increment(current.sequence), hasher))
                                    .thenCompose(expected -> writerKey(owner, w.writer)
                                            .thenCompose(key -> key.unsignMessage(w.blockListSignature.get())
                                                    .thenApply(signed -> {
                                                        if (! Arrays.equals(signed, expected))
                                                            throw new IllegalStateException("Invalid block list signature for " + w.writer);
                                                        return true;
                                                    })));
                        })
                        .collect(Collectors.toList()))
                .thenApply(x -> true);
    }

    private static void markReachable(Cid current, Set<Cid> reachable, Map<Cid, byte[]> cborInCall) {
        if (! reachable.add(current))
            return;
        byte[] block = cborInCall.get(current);
        if (block == null)
            return;
        for (Multihash link : CborObject.fromByteArray(block).links()) {
            Cid c = (Cid) link;
            if (! c.isIdentity())
                markReachable(c, reachable, cborInCall);
        }
    }

    /** A transaction supplied by the caller stays theirs to close: they may be sending several calls
     *  under it, and the blocks it is holding are only safe until it closes.
     */
    private CompletableFuture<List<Cid>> withTransaction(PublicKeyHash owner,
                                                         Optional<TransactionId> supplied,
                                                         Function<TransactionId, CompletableFuture<List<Cid>>> body) {
        if (supplied.isPresent())
            return body.apply(supplied.get());
        return target.startTransaction(owner)
                .thenCompose(tid -> body.apply(tid)
                        .thenCompose(res -> target.closeTransaction(owner, tid).thenApply(x -> res)));
    }

    private CompletableFuture<List<Cid>> writeBlocks(PublicKeyHash owner, BulkCommit commit, TransactionId tid) {
        List<Cid> written = new ArrayList<>();
        return Futures.reduceAll(commit.writers, true,
                        (done, w) -> writeBlocks(owner, w, tid).thenApply(cids -> {
                            written.addAll(cids);
                            return done;
                        }),
                        (x, y) -> x && y)
                .thenApply(x -> written);
    }

    private CompletableFuture<List<Cid>> writeBlocks(PublicKeyHash owner, WriterCommit w, TransactionId tid) {
        return (w.cborBlocks.isEmpty() ?
                Futures.of(Collections.<Cid>emptyList()) :
                target.put(owner, w.writer, unsigned(w.cborBlocks.size()), w.cborBlocks, tid))
                .thenCompose(cbor -> (w.rawBlocks.isEmpty() ?
                        Futures.of(Collections.<Cid>emptyList()) :
                        target.putRaw(owner, w.writer, unsigned(w.rawBlocks.size()), w.rawBlocks, tid, x -> {}))
                        .thenApply(raw -> {
                            List<Cid> all = new ArrayList<>(cbor);
                            all.addAll(raw);
                            return all;
                        }));
    }

    private static List<byte[]> unsigned(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> new byte[0])
                .collect(Collectors.toList());
    }
}
