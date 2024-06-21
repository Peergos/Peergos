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
import peergos.shared.util.ProgressConsumer;

import java.time.*;
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
        public CompletableFuture<List<byte[]>> getChampLookup(PublicKeyHash owner, Cid root, byte[] champKey, Optional<BatWithId> bat, Optional<Cid> committedRoot) {
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
        public CompletableFuture<Optional<Integer>> getSize(Multihash block) {
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
