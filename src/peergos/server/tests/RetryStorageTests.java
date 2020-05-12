package peergos.server.tests;

import org.junit.Assert;
import org.junit.Test;
import peergos.server.storage.RAMStorage;
import peergos.shared.cbor.CborObject;
import peergos.shared.crypto.hash.PublicKeyHash;
import peergos.shared.io.ipfs.multihash.Multihash;
import peergos.shared.storage.BlockStoreProperties;
import peergos.shared.storage.ContentAddressedStorage;
import peergos.shared.storage.RetryStorage;
import peergos.shared.storage.TransactionId;
import peergos.shared.util.ProgressConsumer;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class RetryStorageTests {

    public class FailingStorage implements ContentAddressedStorage {
        private int counter = 1;
        private final int retryLimit;
        public FailingStorage(int retryLimit) {
            this.retryLimit = retryLimit;
        }
        @Override
        public CompletableFuture<Multihash> id() {
            if(counter++ % retryLimit != 0) {
                return CompletableFuture.failedFuture(new Error("failure!"));
            }else{
                counter=1;
                return CompletableFuture.completedFuture(new Multihash(Multihash.Type.sha2_256, new byte[32]));
            }
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
        public CompletableFuture<Boolean> gc() {
            if(counter++ % retryLimit != 0) {
                return CompletableFuture.failedFuture(new Error("failure!"));
            }else {
                counter=1;
                return CompletableFuture.completedFuture(true);
            }
        }

        @Override
        public CompletableFuture<List<Multihash>> put(PublicKeyHash owner,
                                                      PublicKeyHash writer,
                                                      List<byte[]> signatures,
                                                      List<byte[]> blocks,
                                                      TransactionId tid) {
            return put(writer, blocks, false);
        }

        @Override
        public CompletableFuture<List<Multihash>> putRaw(PublicKeyHash owner,
                                                         PublicKeyHash writer,
                                                         List<byte[]> signatures,
                                                         List<byte[]> blocks,
                                                         TransactionId tid,
                                                         ProgressConsumer<Long> progressConsumer) {
            return put(writer, blocks, true);
        }

        private CompletableFuture<List<Multihash>> put(PublicKeyHash writer, List<byte[]> blocks, boolean isRaw) {
            if(counter++ % retryLimit != 0) {
                return CompletableFuture.failedFuture(new Error("failure!"));
            }else{
                counter=1;
                return CompletableFuture.completedFuture(new ArrayList<>());
            }
        }


        @Override
        public CompletableFuture<Optional<byte[]>> getRaw(Multihash object) {
            if(counter++ % retryLimit != 0) {
                return CompletableFuture.failedFuture(new Error("failure!"));
            }else {
                counter=1;
                return CompletableFuture.completedFuture(Optional.empty());
            }
        }

        @Override
        public CompletableFuture<Optional<CborObject>> get(Multihash hash) {
            if(counter++ % retryLimit != 0) {
                return CompletableFuture.failedFuture(new Error("failure!"));
            }else {
                counter=1;
                return CompletableFuture.completedFuture(Optional.empty());
            }
        }

        @Override
        public CompletableFuture<List<Multihash>> recursivePin(PublicKeyHash owner, Multihash h) {
            if(counter++ % retryLimit != 0) {
                return CompletableFuture.failedFuture(new Error("failure!"));
            }else {
                counter=1;
                return CompletableFuture.completedFuture(Arrays.asList(h));
            }
        }

        @Override
        public CompletableFuture<List<Multihash>> recursiveUnpin(PublicKeyHash owner, Multihash h) {
            if(counter++ % retryLimit != 0) {
                return CompletableFuture.failedFuture(new Error("failure!"));
            }else {
                counter=1;
                return CompletableFuture.completedFuture(Arrays.asList(h));
            }
        }

        @Override
        public CompletableFuture<List<Multihash>> pinUpdate(PublicKeyHash owner, Multihash existing, Multihash updated) {
            if(counter++ % retryLimit != 0) {
                return CompletableFuture.failedFuture(new Error("failure!"));
            }else {
                counter=1;
                return CompletableFuture.completedFuture(Arrays.asList(existing, updated));
            }
        }

        @Override
        public CompletableFuture<List<Multihash>> getLinks(Multihash root) {
            if(counter++ % retryLimit != 0) {
                return CompletableFuture.failedFuture(new Error("failure!"));
            }else {
                counter=1;
                return CompletableFuture.completedFuture(Collections.emptyList());
            }
        }

        @Override
        public CompletableFuture<Optional<Integer>> getSize(Multihash block) {
            if(counter++ % retryLimit != 0) {
                return CompletableFuture.failedFuture(new Error("failure!"));
            }else {
                counter=1;
                return CompletableFuture.completedFuture(Optional.empty());
            }
        }
    }

    @Test
    public void callMethod() {
        ContentAddressedStorage storage = new RetryStorage(new RAMStorage());

        BlockStoreProperties props = storage.blockStoreProperties().join();
        Assert.assertNotNull("props should not be null", props);
    }
    @Test
    public void retryMethodSuccess() {
        ContentAddressedStorage storage = new RetryStorage(new FailingStorage(3));

        Boolean result = storage.gc().join();
        Assert.assertNotNull("Retry should succeed", result);
    }
    @Test
    public void retryMethodFailure() {
        ContentAddressedStorage storage = new RetryStorage(new FailingStorage(4));

        try {
            Boolean result = storage.gc().join();
            Assert.assertTrue("Should throw exception", false);
        } catch (Exception e) {
            System.currentTimeMillis();
        }
    }
}
