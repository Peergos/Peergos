package peergos.server.storage;

import org.peergos.EmbeddedIpfs;
import org.peergos.HashedBlock;
import org.peergos.Want;
import peergos.shared.cbor.CborObject;
import peergos.shared.crypto.hash.Hasher;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.user.HttpPoster;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

public class NabuDeletableStorage extends NabuStorage implements DeletableContentAddressedStorage {

    public NabuDeletableStorage(EmbeddedIpfs ipfs, HttpPoster poster, boolean isPeergosServer, Hasher hasher) {
        super(ipfs, poster, isPeergosServer, hasher);
    }


    @Override
    public Stream<Cid> getAllBlockHashes() {
        List<io.ipfs.cid.Cid> cids = ipfs.blockstore.refs().join();
        return cids.stream().map(c -> Cid.decode(c.toString()));
    }

    @Override
    public Stream<BlockVersion> getAllBlockHashVersions() {
        return getAllBlockHashes().map(c -> new BlockVersion(c, null, true));
    }

    @Override
    public void delete(Cid hash) {
        ipfs.blockstore.rm(io.ipfs.cid.Cid.decode(hash.toString())).join();
    }

    @Override
    public void bloomAdd(Multihash hash) {
        ipfs.blockstore.bloomAdd(io.ipfs.cid.Cid.decode(hash.toString())).join();
    }

    @Override
    public boolean hasBlock(Cid hash) {
        return ipfs.blockstore.has(io.ipfs.cid.Cid.decode(hash.toString())).join();
    }

    @Override
    public List<Multihash> getOpenTransactionBlocks() {
        throw new IllegalStateException("Unimplemented!");
    }

    @Override
    public void clearOldTransactions(long cutoffMillis) {
        throw new IllegalStateException("Unimplemented!");
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(Cid hash, String auth) {
        if (hash.isIdentity())
            return CompletableFuture.completedFuture(Optional.of(CborObject.fromByteArray(hash.getHash())));

        Optional<String> authOpt = auth == null || auth.length() == 0 ? Optional.empty() : Optional.of(auth);
        List<HashedBlock> block = ipfs.getBlocks(List.of(new Want(io.ipfs.cid.Cid.decode(hash.toString()), authOpt)), Collections.emptySet(), false);
        Optional<CborObject> cborOpt = block.get(0).block.length == 0 ? Optional.empty() : Optional.of(CborObject.fromByteArray(block.get(0).block));
        return CompletableFuture.completedFuture(cborOpt);
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(Cid hash, String auth) {
        if (hash.isIdentity())
            return CompletableFuture.completedFuture(Optional.of(hash.getHash()));
        Optional<String> authOpt = auth == null || auth.length() == 0 ? Optional.empty() : Optional.of(auth);
        List<HashedBlock> block = ipfs.getBlocks(List.of(new Want(io.ipfs.cid.Cid.decode(hash.toString()), authOpt)), Collections.emptySet(), false);
        Optional<byte[]> bytes = block.get(0).block.length == 0 ? Optional.empty() : Optional.of(block.get(0).block);
        return CompletableFuture.completedFuture(bytes);
    }
}
