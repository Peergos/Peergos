package peergos.server.storage;

import io.libp2p.core.PeerId;
import org.peergos.EmbeddedIpfs;
import org.peergos.HashedBlock;
import org.peergos.Want;
import peergos.shared.cbor.CborObject;
import peergos.shared.crypto.hash.Hasher;
import peergos.shared.crypto.hash.PublicKeyHash;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.storage.ContentAddressedStorage;
import peergos.shared.storage.TransactionId;
import peergos.shared.storage.auth.BatWithId;
import peergos.shared.user.HttpPoster;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class NabuStorage extends ContentAddressedStorage.HTTP {

    protected final EmbeddedIpfs ipfs;
    public NabuStorage(EmbeddedIpfs ipfs, HttpPoster poster, boolean isPeergosServer, Hasher hasher) {
        super(poster, isPeergosServer, hasher);
        this.ipfs = ipfs;
    }

    @Override
    public CompletableFuture<Cid> id() {
        PeerId peerId = ipfs.node.getPeerId();
        return CompletableFuture.completedFuture(Cid.decodePeerId(peerId.toBase58()));
    }

    @Override
    protected CompletableFuture<List<Cid>> put(PublicKeyHash owner,
                                               PublicKeyHash writer,
                                               List<byte[]> signatures,
                                               List<byte[]> blocks,
                                               String format,
                                               TransactionId tid) {
        for (byte[] block : blocks) {
            if (block.length > MAX_BLOCK_SIZE)
                throw new IllegalStateException("Invalid block size: " + block.length
                        + ", blocks must be smaller than 1MiB!");
        }
        List<Cid> cidList = new ArrayList<>(blocks.size());
        for (byte[] block : blocks) {
            io.ipfs.cid.Cid cid = ipfs.blockstore.put(block, io.ipfs.cid.Cid.Codec.lookupIPLDName(format)).join();
            cidList.add(Cid.decode(cid.toString()));
        }
        return CompletableFuture.completedFuture(cidList);
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(Cid hash, Optional<BatWithId> bat) {
        if (hash.isIdentity())
            return CompletableFuture.completedFuture(Optional.of(CborObject.fromByteArray(hash.getHash())));
        if (!isPeergosServer) {
            return super.get(hash, bat);
        } else {
            Optional<String> auth = bat.isPresent() ? Optional.of(bat.get().encode()) : Optional.empty();
            List<HashedBlock> block = ipfs.getBlocks(
                    List.of(new Want(io.ipfs.cid.Cid.decode(hash.toString()), auth))
                    , Collections.emptySet(), true);
            if (!block.isEmpty()) {
                return CompletableFuture.completedFuture(Optional.of(CborObject.fromByteArray(block.get(0).block)));
            }
            return CompletableFuture.completedFuture(Optional.empty());
        }
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(Cid hash, Optional<BatWithId> bat) {
        if (hash.isIdentity())
            return CompletableFuture.completedFuture(Optional.of(hash.getHash()));
        if (!isPeergosServer) {
            return super.getRaw(hash, bat);
        } else {
            Optional<String> auth = bat.isPresent() ? Optional.of(bat.get().encode()) : Optional.empty();
            List<HashedBlock> block = ipfs.getBlocks(
                    List.of(new Want(io.ipfs.cid.Cid.decode(hash.toString()), auth))
                    , Collections.emptySet(), true);
            if (!block.isEmpty()) {
                return CompletableFuture.completedFuture(Optional.of(block.get(0).block));
            }
            return CompletableFuture.completedFuture(Optional.empty());
        }
    }

    @Override
    public CompletableFuture<Optional<Integer>> getSize(Multihash block) {
        List<HashedBlock> found = ipfs.getBlocks(List.of(new Want(io.ipfs.cid.Cid.decode(block.toString()), Optional.of("letmein"))), Collections.emptySet(), false);
        if (! found.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.of(found.get(0).block.length));
        } else {
            return CompletableFuture.completedFuture(Optional.empty());
        }
    }
}

