package peergos.shared.inode;

import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.hamt.*;
import peergos.shared.storage.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

public class DirectoryInode implements Cborable {
    // Inodes caps are < 512 bytes, champs can have 32 mappings max per node. 512 KiB block size limit => 32 max inlined
    public static final int MAX_CHILDREN_INLINED = 32;

    public final Either<List<InodeCap>, Champ<InodeCap>> children;
    private final Hasher writeHasher;
    public final int bitWidth;
    public final PublicKeyHash owner;
    private final Function<ByteArrayWrapper, CompletableFuture<byte[]>> keyHasher;
    private final ContentAddressedStorage storage;

    public DirectoryInode(List<InodeCap> children,
                          Hasher writeHasher,
                          int bitWidth,
                          PublicKeyHash owner,
                          Function<ByteArrayWrapper, CompletableFuture<byte[]>> keyHasher,
                          ContentAddressedStorage storage) {
        this.children = Either.a(children);
        this.writeHasher = writeHasher;
        this.bitWidth = bitWidth;
        this.owner = owner;
        this.keyHasher = keyHasher;
        this.storage = storage;
    }

    public DirectoryInode(Champ<InodeCap> children,
                          Hasher writeHasher,
                          int bitWidth,
                          PublicKeyHash owner,
                          Function<ByteArrayWrapper, CompletableFuture<byte[]>> keyHasher,
                          ContentAddressedStorage storage) {
        this.children = Either.b(children);
        this.writeHasher = writeHasher;
        this.bitWidth = bitWidth;
        this.owner = owner;
        this.keyHasher = keyHasher;
        this.storage = storage;
    }

    private static Champ<InodeCap> buildChamp(Cborable rootCbor) {
        return Champ.fromCbor(rootCbor, InodeCap::fromCbor);
    }

    public CompletableFuture<Optional<InodeCap>> getChild(String name) {
        if (children.isA())
            return Futures.of(children.a().stream().filter(i -> i.inode.name.name.equals(name)).findFirst());
        ByteArrayWrapper key = new ByteArrayWrapper(name.getBytes());
        return keyHasher.apply(key).thenCompose(keyHash -> children.b().get(owner, key, keyHash, 0, bitWidth, storage));
    }

    public CompletableFuture<Boolean> hasMoreThanOneChild() {
        if (children.isA())
            return Futures.of(children.a().size() > 1);
        return Futures.of(children.b().hasMultipleMappings());
    }

    public CompletableFuture<List<InodeCap>> getChildren() {
        if (children.isA())
            return Futures.of(children.a());
        return children.b().reduceAllMappings(owner, new ArrayList<>(), (acc, p) -> {
            p.right.ifPresent(acc::add);
            return Futures.of(acc);
        }, storage);
    }

    public CompletableFuture<DirectoryInode> addChild(InodeCap child,
                                                      PublicKeyHash owner,
                                                      SigningPrivateKeyAndPublicHash writer,
                                                      TransactionId tid) {
        if (children.isA() && children.a().size() < MAX_CHILDREN_INLINED)
            return Futures.of(new DirectoryInode(Stream.concat(children.a().stream().filter(c -> ! c.inode.equals(child.inode)), Stream.of(child))
                    .collect(Collectors.toList()), writeHasher, bitWidth, owner, keyHasher, storage));

        ByteArrayWrapper key = toChampKey(child);
        return keyHasher.apply(key).thenCompose(keyHash ->
                (children.isA() ?
                        buildChamp(children.a(), owner, writer, writeHasher, bitWidth, keyHasher, storage, tid)
                                .thenApply(d -> d.children.b()) :
                        Futures.of(children.b())
                ).thenCompose(champ -> champ.put(owner, writer, key, keyHash, 0, Optional.empty(), Optional.of(child), bitWidth,
                        ChampWrapper.MAX_HASH_COLLISIONS_PER_LEVEL, Optional.empty(), keyHasher, tid, storage, writeHasher, null)
                        .thenApply(rootPair -> new DirectoryInode(rootPair.left, writeHasher, bitWidth, owner, keyHasher, storage)))
        );
    }

    private static ByteArrayWrapper toChampKey(InodeCap val) {
        return new ByteArrayWrapper(val.inode.name.name.getBytes());
    }

    public CompletableFuture<DirectoryInode> removeChild(InodeCap child,
                                                         PublicKeyHash owner,
                                                         SigningPrivateKeyAndPublicHash writer,
                                                         TransactionId tid) {
        if (children.isA())
            return Futures.of(new DirectoryInode(children.a().stream()
                    .filter(c -> ! c.equals(child))
                    .collect(Collectors.toList()), writeHasher, bitWidth, owner, keyHasher, storage));
        ByteArrayWrapper key = new ByteArrayWrapper(child.inode.name.name.getBytes());
        return keyHasher.apply(key).thenCompose(keyHash ->
                children.b().remove(owner, writer, key, keyHash, 0, Optional.of(child), bitWidth,
                        ChampWrapper.MAX_HASH_COLLISIONS_PER_LEVEL, Optional.empty(), tid, storage, writeHasher, null)
                        .thenApply(rootPair -> new DirectoryInode(rootPair.left, writeHasher, bitWidth, owner, keyHasher, storage)));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DirectoryInode that = (DirectoryInode) o;
        return children.equals(that.children);
    }

    @Override
    public int hashCode() {
        return Objects.hash(children);
    }

    public static CompletableFuture<DirectoryInode> buildChamp(List<InodeCap> children,
                                                               PublicKeyHash owner,
                                                               SigningPrivateKeyAndPublicHash writer,
                                                               Hasher writeHasher,
                                                               int bitWidth,
                                                               Function<ByteArrayWrapper, CompletableFuture<byte[]>> keyHasher,
                                                               ContentAddressedStorage storage,
                                                               TransactionId tid) {
        return Futures.reduceAll(children, Champ.empty(InodeCap::fromCbor),
                (c, v) -> keyHasher.apply(toChampKey(v)).thenCompose(keyHash ->
                        c.put(owner, writer, toChampKey(v), keyHash, 0, Optional.empty(), Optional.of(v), bitWidth,
                                ChampWrapper.MAX_HASH_COLLISIONS_PER_LEVEL, Optional.empty(), keyHasher, tid, storage, writeHasher, null))
                        .thenApply(p -> p.left),
                (a, b) -> b)
                .thenApply(champ -> new DirectoryInode(champ, writeHasher, bitWidth, owner, keyHasher, storage));
    }

    public static DirectoryInode empty(Hasher writeHasher,
                                       int bitWidth,
                                       PublicKeyHash owner,
                                       Function<ByteArrayWrapper, CompletableFuture<byte[]>> keyHasher,
                                       ContentAddressedStorage storage) {
        return new DirectoryInode(Collections.emptyList(), writeHasher, bitWidth, owner, keyHasher, storage);
    }

    @Override
    public CborObject toCbor() {
        if (children.isA()) {
            SortedMap<String, Cborable> state = new TreeMap<>();
            state.put("c", new CborObject.CborList(children.a().stream().map(InodeCap::toCbor)
                    .collect(Collectors.toList())));
            return CborObject.CborMap.build(state);
        }
        return children.b().toCbor();
    }

    public static DirectoryInode fromCbor(Cborable cbor,
                                          Hasher writeHasher,
                                          int bitWidth,
                                          PublicKeyHash owner,
                                          Function<ByteArrayWrapper, CompletableFuture<byte[]>> keyHasher,
                                          ContentAddressedStorage storage) {
        if (cbor instanceof CborObject.CborMap)
            return new DirectoryInode(((CborObject.CborMap) cbor).getList("c").value.stream()
                    .map(InodeCap::fromCbor)
                    .collect(Collectors.toList()), writeHasher, bitWidth, owner, keyHasher, storage);
        return new DirectoryInode(buildChamp(cbor), writeHasher, bitWidth, owner, keyHasher, storage);
    }
}
