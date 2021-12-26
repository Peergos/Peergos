package peergos.shared.storage;

import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

public class WriteFilter extends DelegatingStorage {

    private final ContentAddressedStorage dht;
    private final BiFunction<PublicKeyHash, Integer, Boolean> keyFilter;

    public WriteFilter(ContentAddressedStorage dht, BiFunction<PublicKeyHash, Integer, Boolean> keyFilter) {
        super(dht);
        this.dht = dht;
        this.keyFilter = keyFilter;
    }

    @Override
    public CompletableFuture<List<PresignedUrl>> authWrites(PublicKeyHash owner,
                                                            PublicKeyHash writer,
                                                            List<byte[]> signedHashes,
                                                            List<Integer> blockSizes,
                                                            boolean isRaw,
                                                            TransactionId tid) {
        if (! keyFilter.apply(writer, blockSizes.stream().mapToInt(x -> x).sum()))
            throw new IllegalStateException("Key not allowed to write to this server: " + writer);
        return dht.authWrites(owner, writer, signedHashes, blockSizes, isRaw, tid);
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
        if (! keyFilter.apply(writer, blocks.stream().mapToInt(x -> x.length).sum()))
            throw new IllegalStateException("Key not allowed to write to this server: " + writer);
        return dht.put(owner, writer, signedHashes, blocks, tid);
    }

    @Override
    public CompletableFuture<List<Cid>> putRaw(PublicKeyHash owner,
                                               PublicKeyHash writer,
                                               List<byte[]> signatures,
                                               List<byte[]> blocks,
                                               TransactionId tid,
                                               ProgressConsumer<Long> progressConsumer) {
        if (! keyFilter.apply(writer, blocks.stream().mapToInt(x -> x.length).sum()))
            throw new IllegalStateException("Key not allowed to write to this server: " + writer);
        return dht.putRaw(owner, writer, signatures, blocks, tid, progressConsumer);
    }
}
