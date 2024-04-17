package peergos.server.storage;

import peergos.server.storage.auth.*;
import peergos.shared.cbor.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.storage.*;
import peergos.shared.storage.auth.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

public class AuthedStorage extends DelegatingDeletableStorage {
    private final DeletableContentAddressedStorage target;
    private final BlockRequestAuthoriser authoriser;
    private final Hasher h;
    private final Cid ourNodeId;
    private CoreNode pki;

    public AuthedStorage(DeletableContentAddressedStorage target,
                         BlockRequestAuthoriser authoriser,
                         Hasher h) {
        super(target);
        this.target = target;
        this.ourNodeId = target.id().join();
        this.authoriser = authoriser;
        this.h = h;
    }

    @Override
    public CompletableFuture<Cid> id() {
        return Futures.of(ourNodeId);
    }

    @Override
    public void setPki(CoreNode pki) {
        target.setPki(pki);
        this.pki = pki;
    }

    @Override
    public ContentAddressedStorage directToOrigin() {
        return this;
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(PublicKeyHash owner, Cid hash, Optional<BatWithId> bat) {
        List<Multihash> peerIds = hasBlock(hash) ?
                Collections.emptyList() :
                pki.getStorageProviders(owner);
        return get(peerIds, hash, bat, ourNodeId, h);
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(List<Multihash> peerIds, Cid hash, String auth) {
        return getRaw(peerIds, hash, auth).thenApply(bopt -> bopt.map(CborObject::fromByteArray));
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(List<Multihash> peerIds, Cid hash, Optional<BatWithId> bat, Cid ourId, Hasher h) {
        if (bat.isEmpty())
            return get(peerIds, hash, "");
        return bat.get().bat.generateAuth(hash, ourId, 300, S3Request.currentDatetime(), bat.get().id, h)
                .thenApply(BlockAuth::encode)
                .thenCompose(auth -> get(peerIds, hash, auth));
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(PublicKeyHash owner, Cid hash, Optional<BatWithId> bat) {
        return getRaw(pki.getStorageProviders(owner), hash, bat, ourNodeId, h);
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(List<Multihash> peerIds, Cid hash, String auth) {
        return getRaw(peerIds, hash, auth, true);
    }

    @Override
    public CompletableFuture<BlockMetadata> getBlockMetadata(Cid block) {
        return getRaw(Collections.emptyList(), block, "", false)
                .thenApply(rawOpt -> BlockMetadataStore.extractMetadata(block, rawOpt.get()));
    }

    @Override
    public List<List<Cid>> bulkGetLinks(List<Multihash> peerIds, List<Want> wants) {
        return target.bulkGetLinks(peerIds, wants);
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(List<Multihash> peerIds, Cid hash, String auth, boolean doAuth) {
        return target.getRaw(peerIds, hash, auth).thenApply(bopt -> {
            if (bopt.isEmpty())
                return Optional.empty();
            byte[] block = bopt.get();
            if (doAuth && ! authoriser.allowRead(hash, block, id().join(), auth).join())
                throw new IllegalStateException("Unauthorised!");
            return bopt;
        });
    }

    @Override
    public boolean hasBlock(Cid hash) {
        return target.hasBlock(hash);
    }

    @Override
    public CompletableFuture<List<Cid>> getLinks(Cid root) {
        if (root.codec == Cid.Codec.Raw)
            return CompletableFuture.completedFuture(Collections.emptyList());
        return getRaw(Collections.emptyList(), root, "", false)
                .thenApply(opt -> opt.map(CborObject::fromByteArray))
                .thenApply(opt -> opt
                        .map(cbor -> cbor.links().stream().map(c -> (Cid) c).collect(Collectors.toList()))
                        .orElse(Collections.emptyList())
                );
    }

    @Override
    public CompletableFuture<List<byte[]>> getChampLookup(PublicKeyHash owner, Cid root, byte[] champKey, Optional<BatWithId> bat, Optional<Cid> committedRoot) {
        if (! hasBlock(root))
            return Futures.errored(new IllegalStateException("Champ root not present locally: " + root));
        return getChampLookup(owner, root, champKey, bat, committedRoot, h);
    }

    @Override
    public Stream<Cid> getAllBlockHashes(boolean useBlockstore) {
        return target.getAllBlockHashes(useBlockstore);
    }

    @Override
    public void getAllBlockHashVersions(Consumer<List<BlockVersion>> res) {
        target.getAllBlockHashVersions(res);
    }

    @Override
    public List<Cid> getOpenTransactionBlocks() {
        return target.getOpenTransactionBlocks();
    }

    @Override
    public void clearOldTransactions(long cutoffMillis) {
        target.clearOldTransactions(cutoffMillis);
    }

    @Override
    public void delete(Cid hash) {
        target.delete(hash);
    }
}
