package peergos.shared.storage;

import peergos.shared.cbor.CborObject;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.storage.auth.*;
import peergos.shared.user.fs.FragmentWithHash;
import peergos.shared.util.*;

import java.net.*;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public class RetryStorage implements ContentAddressedStorage {

    private static final Random random = new Random(1);
    private static final ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
    private final ContentAddressedStorage target;
    private final int maxAttempts;

    public RetryStorage(ContentAddressedStorage target, int maxAttempts) {
        this.target = target;
        this.maxAttempts = maxAttempts;
    }

    @Override
    public ContentAddressedStorage directToOrigin() {
        return new RetryStorage(target.directToOrigin(), maxAttempts);
    }

    private static <V> void retryAfter(Supplier<CompletableFuture<V>> method, int milliseconds) {
        executor.schedule(method::get, milliseconds, TimeUnit.MILLISECONDS);
    }

    private static int jitter(int minMilliseconds, int rangeMilliseconds) {
        return minMilliseconds + random.nextInt(rangeMilliseconds);
    }

    private <V> CompletableFuture<V> runWithRetry(Supplier<CompletableFuture<V>> f) {
        return recurse(maxAttempts, maxAttempts, f);
    }

    public static <V> CompletableFuture<V> runWithRetry(int maxAttempts, Supplier<CompletableFuture<V>> f) {
        return recurse(maxAttempts, maxAttempts, f);
    }

    private static <V> CompletableFuture<V> recurse(int retriesLeft, int maxAttempts, Supplier<CompletableFuture<V>> f) {
        CompletableFuture<V> res = new CompletableFuture<>();
        try {
            f.get()
                    .thenAccept(res::complete)
                    .exceptionally(e -> {
                        if (retriesLeft == 1) {
                            res.completeExceptionally(e);
                        } else if (e instanceof StorageQuotaExceededException) {
                            res.completeExceptionally(e);
                        } else if (e instanceof HttpFileNotFoundException) {
                            res.completeExceptionally(e);
                        } else if (e instanceof ConnectException) {
                            res.completeExceptionally(e);
                        } else {
                            retryAfter(() -> recurse(retriesLeft - 1, maxAttempts, f)
                                            .thenAccept(res::complete)
                                            .exceptionally(t -> {
                                                res.completeExceptionally(t);
                                                return null;
                                            }),
                                    jitter((maxAttempts + 1 - retriesLeft) * 1000, 500));
                        }
                        return null;
                    });
        } catch (Throwable t) {
            res.completeExceptionally(t);
        }
        return res;
    }
    @Override
    public CompletableFuture<BlockStoreProperties> blockStoreProperties() {
        return runWithRetry(target::blockStoreProperties);
    }
    @Override
    public CompletableFuture<Cid> id() {
        return runWithRetry(target::id);
    }

    @Override
    public CompletableFuture<List<Cid>> ids() {
        return runWithRetry(target::ids);
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
    public CompletableFuture<List<Cid>> put(PublicKeyHash owner, PublicKeyHash writer, List<byte[]> signatures, List<byte[]> blocks, TransactionId tid) {
        return runWithRetry(() -> target.put(owner, writer, signatures, blocks, tid));
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(PublicKeyHash owner, Cid hash, Optional<BatWithId> bat) {
        return runWithRetry(() -> target.get(owner, hash, bat));
    }

    @Override
    public CompletableFuture<List<Cid>> putRaw(PublicKeyHash owner,
                                               PublicKeyHash writer,
                                               List<byte[]> signatures,
                                               List<byte[]> blocks,
                                               TransactionId tid,
                                               ProgressConsumer<Long> progressCounter) {
        return runWithRetry(() -> target.putRaw(owner, writer, signatures, blocks, tid, progressCounter));
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(PublicKeyHash owner, Cid hash, Optional<BatWithId> bat) {
        return runWithRetry(() -> target.getRaw(owner, hash, bat));
    }

    @Override
    public CompletableFuture<List<byte[]>> getChampLookup(PublicKeyHash owner, Cid root, byte[] champKey, Optional<BatWithId> bat, Optional<Cid> committedRoot) {
        return runWithRetry(() -> target.getChampLookup(owner, root, champKey, bat,committedRoot));
    }

    @Override
    public CompletableFuture<Optional<Integer>> getSize(Multihash block) {
        return runWithRetry(() -> target.getSize(block));
    }

    @Override
    public CompletableFuture<IpnsEntry> getIpnsEntry(Multihash signer) {
        return runWithRetry(() -> target.getIpnsEntry(signer));
    }

    @Override
    public CompletableFuture<List<FragmentWithHash>> downloadFragments(PublicKeyHash owner,
                                                                       List<Cid> hashes,
                                                                       List<BatWithId> bats,
                                                                       Hasher h,
                                                                       ProgressConsumer<Long> monitor,
                                                                       double spaceIncreaseFactor) {
        return runWithRetry(() -> target.downloadFragments(owner, hashes, bats, h, monitor, spaceIncreaseFactor));
    }

    @Override
    public CompletableFuture<List<PresignedUrl>> authReads(List<MirrorCap> blocks) {
        return runWithRetry(() -> target.authReads(blocks));
    }

    @Override
    public CompletableFuture<List<PresignedUrl>> authWrites(PublicKeyHash owner,
                                                            PublicKeyHash writer,
                                                            List<byte[]> signedHashes,
                                                            List<Integer> blockSizes,
                                                            List<List<BatId>> batIds,
                                                            boolean isRaw,
                                                            TransactionId tid) {
        return runWithRetry(() -> target.authWrites(owner, writer, signedHashes, blockSizes, batIds, isRaw, tid));
    }
}
