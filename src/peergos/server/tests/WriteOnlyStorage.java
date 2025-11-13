package peergos.server.tests;

import peergos.server.storage.*;
import peergos.server.storage.auth.Want;
import peergos.shared.cbor.CborObject;
import peergos.shared.corenode.CoreNode;
import peergos.shared.crypto.hash.Hasher;
import peergos.shared.crypto.hash.PublicKeyHash;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.storage.*;
import peergos.shared.storage.auth.BatWithId;
import peergos.shared.user.fs.EncryptedCapability;
import peergos.shared.user.fs.SecretLink;
import peergos.shared.util.EfficientHashMap;
import peergos.shared.util.Futures;
import peergos.shared.util.Pair;
import peergos.shared.util.ProgressConsumer;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Stream;

public class WriteOnlyStorage implements DeletableContentAddressedStorage {
    public final Map<Cid, Boolean> storage = new EfficientHashMap<>();
    private final BlockMetadataStore metadb;

    public WriteOnlyStorage(BlockMetadataStore metadb) {
        this.metadb = metadb;
    }

    public Optional<BlockMetadataStore> getBlockMetadataStore() {
        return Optional.of(metadb);
    }

    @Override
    public Stream<Pair<PublicKeyHash, Cid>> getAllBlockHashes(boolean useBlockstore) {
        return storage.keySet().stream().map(c -> new Pair<>(PublicKeyHash.NULL, c));
    }

    @Override
    public void getAllBlockHashVersions(Consumer<List<BlockVersion>> res) {
        List<BlockVersion> batch = new ArrayList<>();
        for (Cid cid : storage.keySet()) {
            batch.add(new BlockVersion(cid, "hey", true));
            if (batch.size() == 1000) {
                res.accept(batch);
                batch.clear();
            }
        }
        res.accept(batch);
    }

    @Override
    public List<Cid> getOpenTransactionBlocks() {
        return List.of();
    }

    @Override
    public void clearOldTransactions(long cutoffMillis) {

    }

    @Override
    public boolean hasBlock(Cid hash) {
        return storage.containsKey(hash);
    }

    @Override
    public void delete(Cid block) {
        storage.remove(block);
    }

    @Override
    public void setPki(CoreNode pki) {

    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(List<Multihash> peerIds, PublicKeyHash owner, Cid hash, String auth, boolean persistBlock) {
        throw new IllegalStateException("Not implemented!");
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(List<Multihash> peerIds,
                                                      PublicKeyHash owner,
                                                      Cid hash,
                                                      Optional<BatWithId> bat,
                                                      Cid ourId,
                                                      Hasher h,
                                                      boolean doAuth,
                                                      boolean persistBlock) {
        throw new IllegalStateException("Not implemented!");
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(List<Multihash> peerIds, PublicKeyHash owner, Cid hash, String auth, boolean persistBlock) {
        throw new IllegalStateException("Not implemented!");
    }

    @Override
    public List<BlockMetadata> bulkGetLinks(List<Multihash> peerIds, PublicKeyHash owner, List<Want> wants) {
        throw new IllegalStateException("Not implemented!");
    }

    @Override
    public CompletableFuture<String> linkHost(PublicKeyHash owner) {
        throw new IllegalStateException("Not implemented!");
    }

    @Override
    public ContentAddressedStorage directToOrigin() {
        return this;
    }

    @Override
    public Optional<BlockCache> getBlockCache() {
        throw new IllegalStateException("Not implemented!");
    }

    @Override
    public CompletableFuture<Cid> id() {
        return Futures.of(new Cid(1, Cid.Codec.DagCbor, Multihash.Type.sha2_256, new byte[32]));
    }

    @Override
    public CompletableFuture<List<Cid>> ids() {
        throw new IllegalStateException("Not implemented!");
    }

    @Override
    public CompletableFuture<TransactionId> startTransaction(PublicKeyHash owner) {
        throw new IllegalStateException("Not implemented!");
    }

    @Override
    public CompletableFuture<Boolean> closeTransaction(PublicKeyHash owner, TransactionId tid) {
        throw new IllegalStateException("Not implemented!");
    }

    @Override
    public CompletableFuture<List<Cid>> put(PublicKeyHash owner, PublicKeyHash writer, List<byte[]> signedHashes, List<byte[]> blocks, TransactionId tid) {
        return Futures.of(blocks.stream().map(b -> hashToCid(b, false)).toList());
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(PublicKeyHash owner, Cid hash, Optional<BatWithId> bat) {
        throw new IllegalStateException("Not implemented!");
    }

    @Override
    public CompletableFuture<List<Cid>> putRaw(PublicKeyHash owner, PublicKeyHash writer, List<byte[]> signedHashes, List<byte[]> blocks, TransactionId tid, ProgressConsumer<Long> progressCounter) {
        return Futures.of(blocks.stream().map(b -> hashToCid(b, true)).toList());
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(PublicKeyHash owner, Cid hash, Optional<BatWithId> bat) {
        throw new IllegalStateException("Not implemented!");
    }

    @Override
    public CompletableFuture<List<Cid>> getLinks(PublicKeyHash owner, Cid root, List<Multihash> peerids) {
        throw new IllegalStateException("Unimplemented!");
    }

    @Override
    public CompletableFuture<BlockMetadata> getBlockMetadata(PublicKeyHash owner, Cid block) {
        throw new IllegalStateException("Not implemented!");
    }

    @Override
    public CompletableFuture<List<byte[]>> getChampLookup(PublicKeyHash owner, Cid root, List<ChunkMirrorCap> caps, Optional<Cid> committedRoot) {
        throw new IllegalStateException("Not implemented!");
    }

    @Override
    public CompletableFuture<Optional<Integer>> getSize(PublicKeyHash owner, Multihash block) {
        throw new IllegalStateException("Not implemented!");
    }

    @Override
    public CompletableFuture<IpnsEntry> getIpnsEntry(Multihash signer) {
        throw new IllegalStateException("Not implemented!");
    }

    @Override
    public CompletableFuture<EncryptedCapability> getSecretLink(SecretLink link) {
        throw new IllegalStateException("Not implemented!");
    }

    @Override
    public CompletableFuture<LinkCounts> getLinkCounts(String owner, LocalDateTime after, BatWithId mirrorBat) {
        throw new IllegalStateException("Not implemented!");
    }

    public static Cid hashToCid(byte[] input, boolean isRaw) {
        byte[] hash = hash(input);
        return new Cid(1, isRaw ? Cid.Codec.Raw : Cid.Codec.DagCbor, Multihash.Type.sha2_256, hash);
    }

    public static byte[] hash(byte[] input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(input);
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("couldn't find hash algorithm");
        }
    }
}
