package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.storage.*;
import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.storage.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Every write to a client's buffer waits there for the commit, batched or not. */
public class BufferedStorageTests {

    private static final Crypto crypto = Main.initCrypto();

    @Test
    public void batchWritesWaitInTheBuffer() {
        AtomicInteger reachedTarget = new AtomicInteger();
        ContentAddressedStorage target = new DelegatingStorage(new RAMStorage(crypto.hasher)) {
            @Override
            public ContentAddressedStorage directToOrigin() {
                return this;
            }

            @Override
            public CompletableFuture<List<Cid>> put(PublicKeyHash owner, PublicKeyHash writer, List<byte[]> signedHashes,
                                                    List<byte[]> blocks, TransactionId tid) {
                reachedTarget.incrementAndGet();
                return super.put(owner, writer, signedHashes, blocks, tid);
            }

            @Override
            public CompletableFuture<List<Cid>> putRaw(PublicKeyHash owner, PublicKeyHash writer, List<byte[]> signatures,
                                                       List<byte[]> blocks, TransactionId tid, ProgressConsumer<Long> progress) {
                reachedTarget.incrementAndGet();
                return super.putRaw(owner, writer, signatures, blocks, tid, progress);
            }

            @Override
            public CompletableFuture<List<Cid>> putBatch(PublicKeyHash owner, SigningPrivateKeyAndPublicHash signer,
                                                         List<byte[]> blocks, TransactionId tid, Hasher hasher) {
                reachedTarget.incrementAndGet();
                return super.putBatch(owner, signer, blocks, tid, hasher);
            }

            @Override
            public CompletableFuture<List<Cid>> putRawBatch(PublicKeyHash owner, SigningPrivateKeyAndPublicHash signer,
                                                            List<byte[]> blocks, TransactionId tid,
                                                            ProgressConsumer<Long> progress, Hasher hasher) {
                reachedTarget.incrementAndGet();
                return super.putRawBatch(owner, signer, blocks, tid, progress, hasher);
            }
        };
        BufferedStorage buffer = new BufferedStorage(target, crypto.hasher);

        SigningKeyPair pair = SigningKeyPair.random(crypto.random, crypto.signer);
        SigningPrivateKeyAndPublicHash signer = new SigningPrivateKeyAndPublicHash(
                ContentAddressedStorage.hashKey(pair.publicSigningKey), pair.secretSigningKey);
        PublicKeyHash owner = signer.publicKeyHash;
        // a transaction the storage behind has opened, so a write that reaches it is stored rather than refused
        TransactionId tid = target.startTransaction(owner).join();

        byte[] raw = crypto.random.randomBytes(1024);
        byte[] cbor = new CborObject.CborString("a block").serialize();
        Cid rawCid = buffer.putRawBatch(owner, signer, List.of(raw), tid, x -> {}, crypto.hasher).join().get(0);
        Cid cborCid = buffer.putBatch(owner, signer, List.of(cbor), tid, crypto.hasher).join().get(0);

        Assert.assertEquals("nothing is written before the commit", 0, reachedTarget.get());
        Assert.assertFalse(buffer.isEmpty());
        Assert.assertArrayEquals(raw, buffer.getRaw(owner, rawCid, Optional.empty()).join().get());
        Assert.assertArrayEquals(cbor, buffer.get(owner, cborCid, Optional.empty()).join().get().serialize());
    }
}
