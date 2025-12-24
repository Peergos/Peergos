package peergos.server.tests;

import peergos.server.corenode.JdbcIpnsAndSocial;
import peergos.server.space.UsageStore;
import peergos.server.storage.*;
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
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Stream;

public class VersionedWriteOnlyStorage implements DeletableContentAddressedStorage {
    public final Map<PublicKeyHash, Map<Cid, Boolean>> storage = new EfficientHashMap<>();
    private final BlockMetadataStore metadb;
    private final AtomicLong nextVersion = new AtomicLong(0);
    private final Map<Cid, Set<String>> versions = new HashMap<>();

    public VersionedWriteOnlyStorage(BlockMetadataStore metadb) {
        this.metadb = metadb;
    }

    public BlockVersion add(PublicKeyHash owner, Cid c) {
        Set<String> versions = this.versions.computeIfAbsent(c, x -> new HashSet<>());
        String version = Long.toString(nextVersion.incrementAndGet());
        versions.add(version);
        storage.computeIfAbsent(owner, o -> new HashMap<>()).put(c, true);
        return new BlockVersion(c, version, true);
    }

    public Optional<BlockMetadataStore> getBlockMetadataStore() {
        return Optional.of(metadb);
    }

    @Override
    public Stream<Pair<PublicKeyHash, Cid>> getAllBlockHashes(PublicKeyHash owner, boolean useBlockstore) {
        return storage.getOrDefault(owner, Collections.emptyMap()).keySet()
                .stream()
                .map(c -> new Pair<>(owner, c));
    }

    @Override
    public Stream<Pair<PublicKeyHash, Cid>> getAllBlockHashes(boolean useBlockstore) {
        return storage.entrySet().stream()
                .flatMap(e -> e.getValue()
                        .keySet()
                        .stream()
                        .map(c -> new Pair<>(e.getKey(), c)));
    }

    @Override
    public void getAllBlockHashVersions(PublicKeyHash owner, Consumer<List<BlockVersion>> res) {
        List<BlockVersion> batch = new ArrayList<>();
        for (PublicKeyHash writer : storage.keySet()) {
            for (Cid cid : storage.get(writer).keySet()) {
                Set<String> versions = this.versions.getOrDefault(cid, Collections.emptySet());
                if (!versions.isEmpty()) {
                    String latest = versions.stream().sorted(Comparator.reverseOrder()).findFirst().get();
                    for (String version : versions) {
                        batch.add(new BlockVersion(cid, version, version.equals(latest)));
                    }
                    if (batch.size() >= 1000) {
                        res.accept(batch);
                        batch.clear();
                    }
                }
            }
        }
        res.accept(batch);
    }

    @Override
    public List<Cid> getOpenTransactionBlocks(PublicKeyHash owner) {
        return List.of();
    }

    @Override
    public void clearOldTransactions(PublicKeyHash owner, long cutoffMillis) {

    }

    @Override
    public boolean hasBlock(PublicKeyHash owner, Cid hash) {
        return storage.get(owner).containsKey(hash);
    }

    @Override
    public void delete(PublicKeyHash owner, Cid block) {
        throw new IllegalStateException();
    }

    @Override
    public void delete(PublicKeyHash owner, BlockVersion v) {
        Set<String> versions = this.versions.get(v.cid);
        if (versions == null || versions.isEmpty())
            return;
        versions.remove(v.version);
        if (versions.isEmpty())
            storage.remove(v.cid);
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
    public CompletableFuture<List<Cid>> getLinks(PublicKeyHash owner, Cid root, List<Multihash> peerids) {
        throw new IllegalStateException("Unimplemented!");
    }

    @Override
    public CompletableFuture<BlockMetadata> getBlockMetadata(PublicKeyHash owner, Cid block) {
        throw new IllegalStateException("Not implemented!");
    }

    @Override
    public CompletableFuture<LinkCounts> getLinkCounts(String owner, LocalDateTime after, BatWithId mirrorBat) {
        throw new IllegalStateException("Not implemented!");
    }

    @Override
    public void partitionByUser(UsageStore usage, JdbcIpnsAndSocial mutable) {
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
