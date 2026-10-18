package peergos.server.tests;

import org.junit.Assert;
import org.junit.Test;
import peergos.server.*;
import peergos.server.storage.*;
import peergos.shared.cbor.CborObject;
import peergos.shared.crypto.hash.PublicKeyHash;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.storage.*;
import peergos.shared.storage.auth.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.Futures;
import peergos.shared.util.ProgressConsumer;

import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

public class RetryStorageTests {

    public class FailingStorage implements ContentAddressedStorage {

        @Override
        public peergos.shared.routing.IdentityKeys identityKeys() {
            return peergos.shared.routing.IdentityKeys.INLINE;
        }
        private int counter = 1;
        private final int retryLimit;
        public FailingStorage(int retryLimit) {
            this.retryLimit = retryLimit;
        }

        @Override
        public ContentAddressedStorage directToOrigin() {
            return this;
        }

        @Override
        public CompletableFuture<Cid> id() {
            if(counter++ % retryLimit != 0) {
                return CompletableFuture.failedFuture(new Error("failure!"));
            }else{
                counter=1;
                return CompletableFuture.completedFuture(new Cid(1, Cid.Codec.LibP2pKey, Multihash.Type.sha2_256, new byte[32]));
            }
        }

        @Override
        public CompletableFuture<List<Cid>> ids() {
            if(counter++ % retryLimit != 0) {
                return CompletableFuture.failedFuture(new Error("failure!"));
            }else{
                counter=1;
                return CompletableFuture.completedFuture(List.of(new Cid(1, Cid.Codec.LibP2pKey, Multihash.Type.sha2_256, new byte[32])));
            }
        }

        @Override
        public CompletableFuture<String> linkHost(PublicKeyHash owner) {
            return Futures.of("localhost");
        }

        @Override
        public CompletableFuture<TransactionId> startTransaction(PublicKeyHash owner) {
            if(counter++ % retryLimit != 0) {
                return CompletableFuture.failedFuture(new Error("failure!"));
            }else {
                counter=1;
                return CompletableFuture.completedFuture(new TransactionId(Long.toString(System.currentTimeMillis())));
            }
        }

        @Override
        public CompletableFuture<Boolean> closeTransaction(PublicKeyHash owner, TransactionId tid) {
            if(counter++ % retryLimit != 0) {
                return CompletableFuture.failedFuture(new Error("failure!"));
            }else {
                counter=1;
                return CompletableFuture.completedFuture(true);
            }
        }

        @Override
        public CompletableFuture<List<Cid>> put(PublicKeyHash owner,
                                                PublicKeyHash writer,
                                                List<byte[]> signatures,
                                                List<byte[]> blocks,
                                                TransactionId tid) {
            return put(writer, blocks, false);
        }

        @Override
        public CompletableFuture<List<Cid>> putRaw(PublicKeyHash owner,
                                                   PublicKeyHash writer,
                                                   List<byte[]> signatures,
                                                   List<byte[]> blocks,
                                                   TransactionId tid,
                                                   ProgressConsumer<Long> progressConsumer) {
            return put(writer, blocks, true);
        }

        private CompletableFuture<List<Cid>> put(PublicKeyHash writer, List<byte[]> blocks, boolean isRaw) {
            if(counter++ % retryLimit != 0) {
                return CompletableFuture.failedFuture(new Error("failure!"));
            }else{
                counter=1;
                return CompletableFuture.completedFuture(new ArrayList<>());
            }
        }


        @Override
        public CompletableFuture<Optional<byte[]>> getRaw(PublicKeyHash owner, Cid object, Optional<BatWithId> bat) {
            if(counter++ % retryLimit != 0) {
                return CompletableFuture.failedFuture(new Error("failure!"));
            }else {
                counter=1;
                return CompletableFuture.completedFuture(Optional.empty());
            }
        }

        @Override
        public CompletableFuture<Optional<CborObject>> get(PublicKeyHash owner, Cid hash, Optional<BatWithId> bat) {
            if(counter++ % retryLimit != 0) {
                return CompletableFuture.failedFuture(new Error("failure!"));
            }else {
                counter=1;
                return CompletableFuture.completedFuture(Optional.empty());
            }
        }

        @Override
        public CompletableFuture<List<byte[]>> getChampLookup(PublicKeyHash owner, Cid root, List<ChunkMirrorCap> caps, Optional<Cid> committedRoot) {
            if(counter++ % retryLimit != 0) {
                return CompletableFuture.failedFuture(new Error("failure!"));
            }else {
                counter=1;
                return CompletableFuture.completedFuture(Collections.emptyList());
            }
        }

        @Override
        public CompletableFuture<EncryptedCapability> getSecretLink(SecretLink link) {
            throw new IllegalStateException("Unimplemented!");
        }

        @Override
        public CompletableFuture<LinkCounts> getLinkCounts(String owner, LocalDateTime after, BatWithId mirrorBat) {
            throw new IllegalStateException("Unimplemented!");
        }

        @Override
        public CompletableFuture<Optional<Integer>> getSize(PublicKeyHash owner, Multihash block) {
            if(counter++ % retryLimit != 0) {
                return CompletableFuture.failedFuture(new Error("failure!"));
            }else {
                counter=1;
                return CompletableFuture.completedFuture(Optional.empty());
            }
        }

        @Override
        public CompletableFuture<IpnsEntry> getIpnsEntry(Multihash signer) {
            throw new IllegalStateException("Unimplemented!");
        }

        @Override
        public Optional<BlockCache> getBlockCache() {
            return Optional.empty();
        }
    }

    public class FailingCommits extends FailingStorage {
        private final int failures;
        private final Supplier<RuntimeException> failure;
        public int calls = 0;

        public FailingCommits(int failures, Supplier<RuntimeException> failure) {
            super(1);
            this.failures = failures;
            this.failure = failure;
        }

        @Override
        public CompletableFuture<List<Cid>> bulkCommit(PublicKeyHash owner, BulkCommit commit) {
            if (calls++ < failures)
                return CompletableFuture.failedFuture(failure.get());
            return Futures.of(Collections.emptyList());
        }
    }

    @Test
    public void bulkCommitRetriedOnTooManyRequests() {
        FailingCommits target = new FailingCommits(2, () -> new TooManyRequestsException("Too Many Requests"));
        new RetryStorage(target, 3).bulkCommit(null, null).join();
        Assert.assertEquals(3, target.calls);
    }

    @Test
    public void bulkCommitNotRetriedOnOtherFailures() {
        List<Supplier<RuntimeException>> failures = List.of(
                RateLimitException::new, // a 502 or 504, after which the commit may have landed
                () -> new MajorRateLimitException("Rate Limit: User request limit exceeded"),
                () -> new IllegalStateException("failure!"));
        for (Supplier<RuntimeException> failure : failures) {
            FailingCommits target = new FailingCommits(1, failure);
            try {
                new RetryStorage(target, 3).bulkCommit(null, null).join();
                Assert.fail("Should throw exception");
            } catch (CompletionException expected) {}
            Assert.assertEquals(1, target.calls);
        }
    }

    @Test
    public void callMethod() {
        ContentAddressedStorage storage = new RetryStorage(new RAMStorage(Main.initCrypto().hasher), 3);

        BlockStoreProperties props = storage.blockStoreProperties().join();
        Assert.assertNotNull("props should not be null", props);
    }
    @Test
    public void retryMethodSuccess() {
        ContentAddressedStorage storage = new RetryStorage(new FailingStorage(3), 3);

        Cid result = storage.id().join();
        Assert.assertNotNull("Retry should succeed", result);
    }
    @Test
    public void retryMethodFailure() {
        ContentAddressedStorage storage = new RetryStorage(new FailingStorage(4), 3);

        try {
            Cid result = storage.id().join();
            Assert.assertTrue("Should throw exception", false);
        } catch (Exception e) {
            System.currentTimeMillis();
        }
    }
}
