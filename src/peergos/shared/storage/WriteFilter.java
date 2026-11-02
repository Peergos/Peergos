package peergos.shared.storage;

import peergos.shared.crypto.hash.*;
import peergos.shared.storage.auth.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

public class WriteFilter extends DelegatingStorage {

    private final ContentAddressedStorage dht;
    /** Whether a writer may write so many bytes, ahead of the pointer update that will name them, under a transaction */
    public interface Check {
        boolean allow(PublicKeyHash owner, PublicKeyHash writer, int size, TransactionId tid);
    }

    private final Check keyFilter;

    public WriteFilter(ContentAddressedStorage dht, Check keyFilter) {
        super(dht);
        this.dht = dht;
        this.keyFilter = keyFilter;
    }

    @Override
    public CompletableFuture<List<PresignedUrl>> authWrites(PublicKeyHash owner,
                                                            PublicKeyHash writer,
                                                            List<byte[]> signedHashes,
                                                            List<Integer> blockSizes,
                                                            List<List<BatId>> batIds,
                                                            boolean isRaw,
                                                            TransactionId tid) {
        long totalSize = blockSizes.stream().mapToLong(Integer::longValue).sum();
        if (totalSize > Integer.MAX_VALUE)
            throw new IllegalStateException("Total write size too large: " + totalSize);
        if (! keyFilter.allow(owner, writer, (int) totalSize, tid))
            throw new IllegalStateException("Key not allowed to write to this server: " + writer);
        if (blockSizes.stream().anyMatch(s -> s > Fragment.MAX_LENGTH_WITH_BAT_PREFIX))
            throw new IllegalStateException("Block too big!");
        return dht.authWrites(owner, writer, signedHashes, blockSizes, batIds, isRaw, tid);
    }

    @Override
    public CompletableFuture<List<PresignedUrl>> authWrites(PublicKeyHash owner,
                                                            PublicKeyHash writer,
                                                            BlockWriteAuth auth,
                                                            boolean isRaw,
                                                            TransactionId tid) {
        long totalSize = auth.sizes.stream().mapToLong(Long::longValue).sum();
        if (totalSize > Integer.MAX_VALUE)
            throw new IllegalStateException("Total write size too large: " + totalSize);
        if (! keyFilter.allow(owner, writer, (int) totalSize, tid))
            throw new IllegalStateException("Key not allowed to write to this server: " + writer);
        if (auth.sizes.stream().anyMatch(s -> s > Fragment.MAX_LENGTH_WITH_BAT_PREFIX))
            throw new IllegalStateException("Block too big!");
        return dht.authWrites(owner, writer, auth, isRaw, tid);
    }

    @Override
    public ContentAddressedStorage directToOrigin() {
        return this;
    }

    @Override
    public CompletableFuture<List<Cid>> put(PublicKeyHash owner,
                                            PublicKeyHash writer,
                                            List<byte[]> signedHashes,
                                            List<byte[]> blocks,
                                            TransactionId tid) {
        long totalSize = blocks.stream().mapToLong(x -> x.length).sum();
        if (totalSize > Integer.MAX_VALUE)
            throw new IllegalStateException("Total write size too large: " + totalSize);
        if (! keyFilter.allow(owner, writer, (int) totalSize, tid))
            throw new IllegalStateException("Key not allowed to write to this server: " + writer);
        if (blocks.stream().anyMatch(b -> b.length > Fragment.MAX_LENGTH_WITH_BAT_PREFIX))
            throw new IllegalStateException("Block too big!");
        return dht.put(owner, writer, signedHashes, blocks, tid);
    }

    @Override
    public CompletableFuture<List<Cid>> putRaw(PublicKeyHash owner,
                                               PublicKeyHash writer,
                                               List<byte[]> signatures,
                                               List<byte[]> blocks,
                                               TransactionId tid,
                                               ProgressConsumer<Long> progressConsumer) {
        long totalSize = blocks.stream().mapToLong(x -> x.length).sum();
        if (totalSize > Integer.MAX_VALUE)
            throw new IllegalStateException("Total write size too large: " + totalSize);
        if (! keyFilter.allow(owner, writer, (int) totalSize, tid))
            throw new IllegalStateException("Key not allowed to write to this server: " + writer);
        if (blocks.stream().anyMatch(b -> b.length > Fragment.MAX_LENGTH_WITH_BAT_PREFIX))
            throw new IllegalStateException("Block too big!");
        return dht.putRaw(owner, writer, signatures, blocks, tid, progressConsumer);
    }
}
