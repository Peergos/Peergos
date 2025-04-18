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
    private final String linkHost;
    private CoreNode pki;

    public AuthedStorage(DeletableContentAddressedStorage target,
                         BlockRequestAuthoriser authoriser,
                         String linkHost,
                         Hasher h) {
        super(target);
        this.target = target;
        this.ourNodeId = target.id().join();
        this.linkHost = linkHost;
        this.authoriser = authoriser;
        this.h = h;
    }

    @Override
    public CompletableFuture<Cid> id() {
        return Futures.of(ourNodeId);
    }

    @Override
    public CompletableFuture<String> linkHost(PublicKeyHash owner) {
        return Futures.of(linkHost);
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
        return get(peerIds, hash, bat, ourNodeId, h, true);
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(List<Multihash> peerIds, Cid hash, String auth, boolean persistBlock) {
        return getRaw(peerIds, hash, auth, persistBlock).thenApply(bopt -> bopt.map(CborObject::fromByteArray));
    }

    public static CompletableFuture<Optional<CborObject>> getWithAbsentMirrorBat(Throwable t,
                                                                                 List<Multihash> peerIds,
                                                                                 Cid hash,
                                                                                 Optional<BatWithId> bat,
                                                                                 Cid ourId,
                                                                                 Hasher h,
                                                                                 DeletableContentAddressedStorage target) {
        if (t.getMessage().startsWith("Unauthorised")) {
            if (! bat.get().id().isInline() && target.hasBlock(hash)) {
                // we are dealing with a mirror bat that we likely don't have locally, we can check the hash to verify it
                return target.getRaw(peerIds, hash, bat, ourId, h, false, false)
                        .thenCompose(rawOpt -> {
                            if (rawOpt.isEmpty())
                                return Futures.errored(t);
                            return BatId.sha256(bat.get().bat, h).thenCompose(hashedBat -> {
                                List<BatId> blockBats = hash.isRaw() ?
                                        Bat.getRawBlockBats(rawOpt.get()) :
                                        Bat.getCborBlockBats(rawOpt.get());
                                boolean correctMirrorBat = blockBats.stream().anyMatch(b -> b.equals(hashedBat));
                                if (correctMirrorBat)
                                    return Futures.of(Optional.of(CborObject.fromByteArray(rawOpt.get())));
                                return Futures.errored(t);
                            });
                        });
            }
        }
        return Futures.errored(t);
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(List<Multihash> peerIds, Cid hash, Optional<BatWithId> bat, Cid ourId, Hasher h, boolean  persistblock) {
        if (bat.isEmpty())
            return get(peerIds, hash, "", persistblock);
        return Futures.asyncExceptionally(() -> bat.get().bat.generateAuth(hash, ourId, 300, S3Request.currentDatetime(), bat.get().id, h)
                .thenApply(BlockAuth::encode)
                .thenCompose(auth -> get(peerIds, hash, auth, persistblock)),
                t -> getWithAbsentMirrorBat(t, peerIds, hash, bat, ourId, h, this));
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(PublicKeyHash owner, Cid hash, Optional<BatWithId> bat) {
        return getRaw(pki.getStorageProviders(owner), hash, bat, ourNodeId, h, true);
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(List<Multihash> peerIds, Cid hash, String auth, boolean persistBlock) {
        return getRaw(peerIds, hash, auth, true, persistBlock);
    }

    @Override
    public CompletableFuture<BlockMetadata> getBlockMetadata(Cid block) {
        return getRaw(Collections.emptyList(), block, "", false, true)
                .thenApply(rawOpt -> BlockMetadataStore.extractMetadata(block, rawOpt.get()));
    }

    @Override
    public List<List<Cid>> bulkGetLinks(List<Multihash> peerIds, List<Want> wants) {
        return target.bulkGetLinks(peerIds, wants);
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(List<Multihash> peerIds, Cid hash, String auth, boolean doAuth, boolean persistBlock) {
        return target.getRaw(peerIds, hash, auth, persistBlock).thenApply(bopt -> {
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
        return getRaw(Collections.emptyList(), root, "", false, true)
                .thenApply(opt -> opt.map(CborObject::fromByteArray))
                .thenApply(opt -> opt
                        .map(cbor -> cbor.links().stream().map(c -> (Cid) c).collect(Collectors.toList()))
                        .orElse(Collections.emptyList())
                );
    }

    @Override
    public CompletableFuture<List<byte[]>> getChampLookup(PublicKeyHash owner, Cid root, List<ChunkMirrorCap> caps, Optional<Cid> committedRoot) {
        if (! hasBlock(root))
            return Futures.errored(new IllegalStateException("Champ root not present locally: " + root));
        return getChampLookup(owner, root, caps, committedRoot, h);
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
