package peergos.shared.inode;

import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.hamt.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.storage.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

/** This implements an async Map<Inode, List<InodeCap>>
 *
 */
public class InodeFileSystem implements Cborable {

    public final long inodeCount;
    private final ChampWrapper<DirectoryInode> champ;
    private final ContentAddressedStorage storage;

    public InodeFileSystem(long inodeCount, ChampWrapper<DirectoryInode> champ, ContentAddressedStorage storage) {
        this.inodeCount = inodeCount;
        this.champ = champ;
        this.storage = storage;
    }

    private CompletableFuture<InodeFileSystem> putValue(PublicKeyHash owner,
                                                        SigningPrivateKeyAndPublicHash writer,
                                                        Inode key,
                                                        Optional<DirectoryInode> existing,
                                                        DirectoryInode value,
                                                        TransactionId tid) {
        byte[] raw = key.serialize();
        return champ.put(owner, writer, raw, existing, value, Optional.empty(), tid)
                .thenApply(h -> new InodeFileSystem(existing.isPresent() ? inodeCount : inodeCount + 1, champ, storage));
    }

    private CompletableFuture<InodeFileSystem> remove(PublicKeyHash owner,
                                                      SigningPrivateKeyAndPublicHash writer,
                                                      Inode key,
                                                      Optional<DirectoryInode> existing,
                                                      TransactionId tid) {
        byte[] raw = key.serialize();
        return champ.remove(owner, writer, raw, existing, Optional.empty(), tid)
                .thenApply(h -> new InodeFileSystem(inodeCount, champ, storage));
    }

    private CompletableFuture<Optional<DirectoryInode>> getValue(Inode key) {
        byte[] raw = key.serialize();
        return champ.get(raw);
    }

    public CompletableFuture<InodeFileSystem> addCap(PublicKeyHash owner,
                                                     SigningPrivateKeyAndPublicHash writer,
                                                     String path,
                                                     AbsoluteCapability cap,
                                                     TransactionId tid) {
        String canonPath = TrieNode.canonicalise(path);
        String[] elements = canonPath.split("/");
        if (elements.length == 1)
            throw new IllegalStateException("You cannot publish your root directory!");
        Inode rootKey = rootKey();
        return getOrMkdir(owner, writer, Optional.empty(), rootKey, tid)
                .thenCompose(p -> p.left.addCapRecurse(owner, writer, rootKey, p.right, elements, cap, tid));
    }

    public static Inode rootKey() {
        return new Inode(0, "");
    }

    public CompletableFuture<InodeFileSystem> removeCap(PublicKeyHash owner,
                                                        SigningPrivateKeyAndPublicHash writer,
                                                        String path,
                                                        TransactionId tid) {
        String canonPath = TrieNode.canonicalise(path);
        String[] elements = canonPath.split("/");
        Inode rootKey = rootKey();
        return getValue(rootKey).thenCompose(dirOpt -> {
            if (dirOpt.isEmpty())
                return Futures.of(this);
            return removeCapRecurse(owner, writer, rootKey, dirOpt.get(), elements, tid)
                    .thenApply(p -> p.left);
        });
    }

    /**
     *
     * @param owner
     * @param writer
     * @param dir
     * @param remainingPath
     * @param tid
     * @return The resulting filesystem, and whether to remove the child from the parent
     */
    private CompletableFuture<Pair<InodeFileSystem, Boolean>> removeCapRecurse(PublicKeyHash owner,
                                                                               SigningPrivateKeyAndPublicHash writer,
                                                                               Inode dirKey,
                                                                               DirectoryInode dir,
                                                                               String[] remainingPath,
                                                                               TransactionId tid) {
        if (remainingPath.length == 0) {
            return Futures.of(new Pair<>(this, true));
        }
        return dir.hasMoreThanOneChild()
                .thenCompose(hasOtherChildren -> dir.getChild(remainingPath[0]).thenCompose(childOpt -> {
                    if (childOpt.isEmpty())
                        return Futures.of(new Pair<>(this, false));
                    if (remainingPath.length == 1)
                        return dir.removeChild(childOpt.get(), owner, writer, tid)
                                .thenCompose(updatedDir -> putValue(owner,
                                        writer, dirKey, Optional.of(dir), updatedDir, tid))
                                .thenApply(f -> new Pair<>(f, ! hasOtherChildren));
                    return getValue(childOpt.get().inode)
                            .thenCompose(childDir ->
                                    childDir.isPresent() ?
                                            removeCapRecurse(owner, writer, childOpt.get().inode, childDir.get(), tail(remainingPath), tid)
                                                    .thenCompose(p -> p.right ?
                                                            dir.removeChild(childOpt.get(), owner, writer, tid)
                                                                    .thenCompose(updatedDir -> p.left.putValue(owner,
                                                                            writer, dirKey, Optional.of(dir), updatedDir, tid))
                                                                    .thenApply(f -> new Pair<>(f, ! hasOtherChildren)) :
                                                            Futures.of(new Pair<>(p.left, ! hasOtherChildren))) :
                                            Futures.of(new Pair<>(this, ! hasOtherChildren)));
                }));
    }

    private CompletableFuture<Pair<InodeFileSystem, DirectoryInode>> getOrMkdir(PublicKeyHash owner,
                                                                                SigningPrivateKeyAndPublicHash writer,
                                                                                Optional<Pair<Inode, DirectoryInode>> parent,
                                                                                Inode childDirKey,
                                                                                TransactionId tid) {
        return getValue(childDirKey).thenCompose(opt -> {
            if (opt.isPresent())
                return Futures.of(new Pair<>(this, opt.get()));
            DirectoryInode empty = DirectoryInode.empty(champ.writeHasher, champ.bitWidth, champ.owner, champ.keyHasher, storage);
            return putValue(owner, writer, childDirKey, Optional.empty(), empty, tid)
                    .thenCompose(f -> {
                        if (parent.isEmpty()) // we are the root
                            return Futures.of(f);
                        return parent.get().right.addChild(childDirKey.withoutCap(), owner, writer, tid)
                                .thenCompose(updatedParent -> f.putValue(owner, writer, parent.get().left,
                                        Optional.of(parent.get().right), updatedParent, tid));
                    }).thenApply(f -> new Pair<>(f, empty));
        });
    }

    private CompletableFuture<InodeFileSystem> addCapRecurse(PublicKeyHash owner,
                                                             SigningPrivateKeyAndPublicHash writer,
                                                             Inode dirKey,
                                                             DirectoryInode dir,
                                                             String[] remainingPath,
                                                             AbsoluteCapability cap,
                                                             TransactionId tid) {
        if (remainingPath.length == 1) {
            // add the cap to this directory
            return dir.getChild(remainingPath[0])
                    .thenCompose(existing -> {
                        Inode childKey = existing.map(ic -> ic.inode)
                                .orElseGet(() -> new Inode(inodeCount, remainingPath[0]));
                        return dir.addChild(new InodeCap(childKey, Optional.of(cap)), owner, writer, tid)
                                .thenCompose(updatedDir -> putValue(owner, writer, dirKey, Optional.of(dir), updatedDir, tid));
                    });
        }
        return dir.getChild(remainingPath[0])
                .thenCompose(childCapOpt -> {
                    if (childCapOpt.isPresent())
                        return getValue(childCapOpt.get().inode)
                                .thenCompose(childOpt -> {
                                    if (childOpt.isPresent())
                                        return addCapRecurse(owner, writer, childCapOpt.get().inode,
                                                childOpt.get(), tail(remainingPath), cap, tid);
                                    // Here a cap was published to a child dir, but not to any descendants of it yet
                                    Inode newDir = new Inode(inodeCount, remainingPath[0]);
                                    // parent is absent so we don't overwrite existing entry there
                                    Optional<Pair<Inode, DirectoryInode>> parent = Optional.empty();
                                    return getOrMkdir(owner, writer, parent, newDir, tid)
                                            .thenCompose(p -> p.left.addCapRecurse(owner, writer, newDir, p.right, tail(remainingPath), cap, tid));
                                });
                    Inode newDir = new Inode(inodeCount, remainingPath[0]);
                    return getOrMkdir(owner, writer, Optional.of(new Pair<>(dirKey, dir)), newDir, tid)
                            .thenCompose(p -> p.left.addCapRecurse(owner, writer, newDir, p.right, tail(remainingPath), cap, tid));
                });
    }

    /**
     *
     * @param path
     * @return The most privileged cap to access the requested path, and any remaining path from the cap
     */
    public CompletableFuture<Optional<Pair<InodeCap, String>>> getByPath(String path) {
        String canonPath = TrieNode.canonicalise(path);
        String[] elements = canonPath.split("/");
        InodeCap start = new InodeCap(rootKey(), Optional.empty());
        return getByPathRecurse(start, elements);
    }

    public CompletableFuture<List<InodeCap>> listDirectory(String path) {
        String canonPath = TrieNode.canonicalise(path);
        String[] elements = canonPath.isEmpty() ? new String[0] : canonPath.split("/");
        InodeCap start = new InodeCap(rootKey(), Optional.empty());
        return listDirectoryRecurse(start, elements);
    }

    private CompletableFuture<List<InodeCap>> listDirectoryRecurse(InodeCap current, String[] elements) {
        return getValue(current.inode)
                .thenCompose(dir -> {
                    if (dir.isEmpty())
                        return Futures.of(Collections.emptyList());
                    if (elements.length == 0)
                        return dir.get().getChildren();
                    return dir.get().getChild(elements[0])
                            .thenCompose(capOpt -> {
                                if (capOpt.isEmpty())
                                    return Futures.of(Collections.emptyList());

                                String[] remainder = tail(elements);
                                return listDirectoryRecurse(capOpt.get(), remainder);
                            });
                });
    }

    private CompletableFuture<Optional<Pair<InodeCap, String>>> getByPathRecurse(InodeCap current, String[] elements) {
        if (elements.length == 0)
            return Futures.of(Optional.of(new Pair<>(current, "")));
        return getValue(current.inode)
                .thenCompose(dir -> dir.isEmpty() ?
                        Futures.of(Optional.empty()) :
                        dir.get().getChild(elements[0]))
                .thenCompose(capOpt -> {
                    if (capOpt.isEmpty())
                        return Futures.of(Optional.empty());
                    // short circuit early if there is a more privileged cap
                    String[] remainder = tail(elements);
                    if (capOpt.get().cap.isPresent()) {
                        String descendantPath = Arrays.stream(remainder).collect(Collectors.joining("/"));
                        return Futures.of(Optional.of(new Pair<>(capOpt.get(), descendantPath)));
                    }
                    return getByPathRecurse(capOpt.get(), remainder);
                });
    }

    public Multihash getRoot() {
        return champ.getRoot();
    }

    @Override
    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("c", new CborObject.CborLong(inodeCount));
        state.put("r", new CborObject.CborMerkleLink(champ.getRoot()));
        return CborObject.CborMap.build(state);
    }

    public static CompletableFuture<InodeFileSystem> build(PublicKeyHash owner,
                                                           Cborable cbor,
                                                           Hasher hasher,
                                                           ContentAddressedStorage storage) {
        if (!(cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor!");
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        long inodeCount = m.getLong("c");
        Multihash root = m.getMerkleLink("r");
        Function<ByteArrayWrapper, CompletableFuture<byte[]>> keyHasher = b -> hasher.sha256(b.data);
        Function<Cborable, DirectoryInode> fromCbor =
                c -> DirectoryInode.fromCbor(c, hasher, ChampWrapper.BIT_WIDTH, owner, keyHasher, storage);
        return ChampWrapper.create(owner, (Cid)root, Optional.empty(), keyHasher, storage, hasher, fromCbor)
                .thenApply(cw -> new InodeFileSystem(inodeCount, cw, storage));
    }

    public static CompletableFuture<InodeFileSystem> createEmpty(PublicKeyHash owner,
                                                                 SigningPrivateKeyAndPublicHash writer,
                                                                 ContentAddressedStorage storage,
                                                                 Hasher hasher,
                                                                 TransactionId tid) {
        Function<ByteArrayWrapper, CompletableFuture<byte[]>> keyHasher = b -> hasher.sha256(b.data);
        Function<Cborable, DirectoryInode> fromCbor =
                c -> DirectoryInode.fromCbor(c, hasher, ChampWrapper.BIT_WIDTH, owner, keyHasher, storage);
        return ChampWrapper.create(owner, writer, keyHasher, tid, storage, hasher, fromCbor)
                .thenApply(cw -> new InodeFileSystem(0, cw, storage));
    }

    private static String[] tail(String[] in) {
        return Arrays.copyOfRange(in, Math.min(1, in.length), in.length);
    }
}
