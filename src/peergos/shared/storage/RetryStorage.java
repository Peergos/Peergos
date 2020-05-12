package peergos.shared.storage;

import peergos.shared.cbor.CborObject;
import peergos.shared.crypto.hash.PublicKeyHash;
import peergos.shared.io.ipfs.multihash.Multihash;
import peergos.shared.user.NativeJSCallback;
import peergos.shared.user.fs.FragmentWithHash;
import peergos.shared.util.ProgressConsumer;

import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

public class RetryStorage implements ContentAddressedStorage {

    private static final int RANDOM_SEED = 987447;
    private static Random random = new Random(RANDOM_SEED);
    private NativeJSCallback callback = new NativeJSCallback();
    private final ContentAddressedStorage target;

    public RetryStorage(ContentAddressedStorage target) {
        this.target = target;
    }

    private <Y> void retryAfter(Callable<CompletableFuture<Y>> method, int milliseconds) {
        long before = System.currentTimeMillis();
        try {
            Thread.sleep(milliseconds + 100); //+100 just to be safe from OS timer precision
        } catch (InterruptedException ie) {}
        long after = System.currentTimeMillis();
        long duration = after - before;
        try {
            if(duration < milliseconds) { //must be javascript as Thread sleep is a noop
                callback.callAfterDelay(method, milliseconds);
            } else {
                method.call();
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw new Error("Not expected! error:" + e.getMessage());
        }
    }

    private int jitter(int minMilliseconds, int rangeMilliseconds){
        return minMilliseconds + random.nextInt(rangeMilliseconds);
    }

    private interface Recursable<T, U, X> {
        U apply(T t, Recursable<T, U, X> r);
    }

    private static <T, U, X> Function<T, U> recurse(Recursable<T, U, X> f) {
        return t -> f.apply(t, f);
    }


    public <Y> CompletableFuture<Y> runWithRetry(Supplier<CompletableFuture<Y>> func) {
        System.out.println("calling....");
        CompletableFuture<Y> res = new CompletableFuture<>();
        Function<Integer, CompletableFuture<Y>> compose = recurse(
            (i, f) -> {
                func.get()
                    .thenAccept(res::complete)
                    .exceptionally(e ->  {
                        if(i == 3) {
                            res.completeExceptionally(e);
                        } else {
                            retryAfter(() -> f.apply(i + 1, f), jitter(i * 1000, 1000));
                        }
                        return null;
                    });
                return res;
            });
        compose.apply(1);
        return res;
    }
    @Override
    public CompletableFuture<BlockStoreProperties> blockStoreProperties() {
        return runWithRetry(() -> target.blockStoreProperties());
    }
    @Override
    public CompletableFuture<Multihash> id() {
        return runWithRetry(() -> target.id());
    }

    @Override
    public CompletableFuture<TransactionId> startTransaction(PublicKeyHash owner) {
        return runWithRetry(() -> target.startTransaction(owner));
    }

    @Override
    public CompletableFuture<Boolean> closeTransaction(PublicKeyHash owner, TransactionId tid) {
        return runWithRetry(() -> target.closeTransaction(owner, tid));
    }

    @Override
    public CompletableFuture<List<Multihash>> put(PublicKeyHash owner, PublicKeyHash writer, List<byte[]> signatures, List<byte[]> blocks, TransactionId tid) {
        return runWithRetry(() -> target.put(owner, writer, signatures, blocks, tid));
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(Multihash hash) {
        return runWithRetry(() -> target.get(hash));
    }

    @Override
    public CompletableFuture<List<Multihash>> putRaw(PublicKeyHash owner,
                                                     PublicKeyHash writer,
                                                     List<byte[]> signatures,
                                                     List<byte[]> blocks,
                                                     TransactionId tid,
                                                     ProgressConsumer<Long> progressCounter) {
        return runWithRetry(() -> target.putRaw(owner, writer, signatures, blocks, tid, progressCounter));
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(Multihash hash) {
        return runWithRetry(() -> target.getRaw(hash));
    }

    @Override
    public CompletableFuture<List<Multihash>> pinUpdate(PublicKeyHash owner, Multihash existing, Multihash updated) {
        return runWithRetry(() -> target.pinUpdate(owner, existing, updated));
    }

    @Override
    public CompletableFuture<List<Multihash>> recursivePin(PublicKeyHash owner, Multihash hash) {
        return runWithRetry(() -> target.recursivePin(owner, hash));
    }

    @Override
    public CompletableFuture<List<Multihash>> recursiveUnpin(PublicKeyHash owner, Multihash hash) {
        return runWithRetry(() -> target.recursiveUnpin(owner, hash));
    }

    @Override
    public CompletableFuture<Boolean> gc() {
        return runWithRetry(() -> target.gc());
    }

    @Override
    public CompletableFuture<List<Multihash>> getLinks(Multihash root) {
        return runWithRetry(() -> target.getLinks(root));
    }

    @Override
    public CompletableFuture<Optional<Integer>> getSize(Multihash block) {
        return runWithRetry(() -> target.getSize(block));
    }

    @Override
    public CompletableFuture<List<FragmentWithHash>> downloadFragments(List<Multihash> hashes,
                                                                       ProgressConsumer<Long> monitor,
                                                                       double spaceIncreaseFactor) {
        return runWithRetry(() -> target.downloadFragments(hashes, monitor, spaceIncreaseFactor));
    }

    @Override
    public CompletableFuture<List<PresignedUrl>> authReads(List<Multihash> blocks) {
        return runWithRetry(() -> target.authReads(blocks));
    }

    @Override
    public CompletableFuture<List<PresignedUrl>> authWrites(PublicKeyHash owner,
                                                            PublicKeyHash writer,
                                                            List<byte[]> signedHashes,
                                                            List<Integer> blockSizes,
                                                            boolean isRaw,
                                                            TransactionId tid) {
        return runWithRetry(() -> target.authWrites(owner, writer, signedHashes, blockSizes, isRaw, tid));
    }
}
