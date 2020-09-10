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
    private final Function<ByteArrayWrapper, CompletableFuture<byte[]>> keyHasher;
    private final ContentAddressedStorage storage;

    public DirectoryInode(List<InodeCap> children,
                          Hasher writeHasher,
                          int bitWidth,
                          Function<ByteArrayWrapper, CompletableFuture<byte[]>> keyHasher,
                          ContentAddressedStorage storage) {
        this.children = Either.a(children);
        this.writeHasher = writeHasher;
        this.bitWidth = bitWidth;
        this.keyHasher = keyHasher;
        this.storage = storage;
    }

    public DirectoryInode(Champ<InodeCap> children,
                          Hasher writeHasher,
                          int bitWidth,
                          Function<ByteArrayWrapper, CompletableFuture<byte[]>> keyHasher,
                          ContentAddressedStorage storage) {
        this.children = Either.b(children);
        this.writeHasher = writeHasher;
        this.bitWidth = bitWidth;
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
        return keyHasher.apply(key).thenCompose(keyHash -> children.b().get(key, keyHash, 0, bitWidth, storage));
    }

    public CompletableFuture<List<InodeCap>> getChildren() {
        if (children.isA())
            return Futures.of(children.a());
        return children.b().applyToAllMappings(new ArrayList<>(), (acc, p) -> {
            p.right.ifPresent(acc::add);
            return Futures.of(acc);
        }, storage);
    }

    public CompletableFuture<DirectoryInode> addChild(InodeCap child,
                                                      PublicKeyHash owner,
                                                      SigningPrivateKeyAndPublicHash writer,
                                                      TransactionId tid) {
        if (children.isA() && children.a().size() < MAX_CHILDREN_INLINED)
            return Futures.of(new DirectoryInode(Stream.concat(children.a().stream(), Stream.of(child))
                    .collect(Collectors.toList()), writeHasher, bitWidth, keyHasher, storage));
        ByteArrayWrapper key = new ByteArrayWrapper(child.inode.name.name.getBytes());
        return keyHasher.apply(key).thenCompose(keyHash ->
                children.b().put(owner, writer, key, keyHash, 0, Optional.empty(), Optional.of(child), bitWidth,
                        ChampWrapper.MAX_HASH_COLLISIONS_PER_LEVEL, keyHasher, tid, storage, writeHasher, null)
                        .thenApply(rootPair -> new DirectoryInode(rootPair.left, writeHasher, bitWidth, keyHasher, storage)));
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

    public static DirectoryInode empty(Hasher writeHasher,
                                       int bitWidth,
                                       Function<ByteArrayWrapper, CompletableFuture<byte[]>> keyHasher,
                                       ContentAddressedStorage storage) {
        return new DirectoryInode(Collections.emptyList(), writeHasher, bitWidth, keyHasher, storage);
    }

    @Override
    public CborObject toCbor() {
        if (children.isA())
            return new CborObject.CborList(children.a().stream().map(InodeCap::toCbor)
                    .collect(Collectors.toList()));
        return children.b().toCbor();
    }

    public static DirectoryInode fromCbor(Cborable cbor,
                                          Hasher writeHasher,
                                          int bitWidth,
                                          Function<ByteArrayWrapper, CompletableFuture<byte[]>> keyHasher,
                                          ContentAddressedStorage storage) {
        if (cbor instanceof CborObject.CborList)
            return new DirectoryInode(((CborObject.CborList) cbor).value.stream()
                    .map(InodeCap::fromCbor)
                    .collect(Collectors.toList()), writeHasher, bitWidth, keyHasher, storage);
        return new DirectoryInode(buildChamp(cbor), writeHasher, bitWidth, keyHasher, storage);
    }
}
