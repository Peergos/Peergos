package peergos.shared.storage;

import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

public class CommittableStorage extends DelegatingStorage {
    private static final int CID_V1 = 1;

    private final ContentAddressedStorage target;
    private final List<PutArgs> pending = new ArrayList<>();

    public CommittableStorage(ContentAddressedStorage target) {
        super(target);
        this.target = target;
    }

    public CompletableFuture<Boolean> flush() {
        List<PutArgs> local;
        synchronized (pending) {
            local = new ArrayList<>(pending);
            pending.clear();
        }
        List<CompletableFuture<Cid>> uploads = local.stream()
                .map(p -> target.put(p.owner, p.writer, p.signature, p.block, p.tid)
                        .thenApply(h -> {
                            if (! h.equals(p.expected))
                                throw new IllegalStateException("Different hash returned from block write than expected!");
                            return h;
                        }))
                .collect(Collectors.toList());
        return Futures.combineAllInOrder(uploads)
                .thenApply(x -> true);
    }

    @Override
    public CompletableFuture<BlockStoreProperties> blockStoreProperties() {
        return target.blockStoreProperties();
    }

    @Override
    public ContentAddressedStorage directToOrigin() {
        return new CommittableStorage(target.directToOrigin());
    }

    @Override
    public CompletableFuture<Cid> put(PublicKeyHash owner,
                                      PublicKeyHash writer,
                                      byte[] signature,
                                      byte[] block,
                                      TransactionId tid) {
        byte[] sha256 = Arrays.copyOfRange(signature, signature.length - 32, signature.length);
        Cid cid = hashToCid(sha256, false);
        synchronized (pending) {
            pending.add(new PutArgs(owner, writer, cid, signature, block, tid));
        }
        return Futures.of(cid);
    }

    public static Cid hashToCid(byte[] sha256, boolean isRaw) {
        return new Cid(CID_V1, isRaw ? Cid.Codec.Raw : Cid.Codec.DagCbor, Multihash.Type.sha2_256, sha256);
    }

    private static class PutArgs {
        public final PublicKeyHash owner, writer;
        public final Cid expected;
        public final byte[] signature, block;
        public final TransactionId tid;

        public PutArgs(PublicKeyHash owner, PublicKeyHash writer, Cid expected, byte[] signature, byte[] block, TransactionId tid) {
            this.owner = owner;
            this.writer = writer;
            this.expected = expected;
            this.signature = signature;
            this.block = block;
            this.tid = tid;
        }
    }
}
