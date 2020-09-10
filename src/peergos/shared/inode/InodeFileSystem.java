package peergos.shared.inode;

import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.hamt.*;
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
public class InodeFileSystem {

    private final long inodeCount;
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
        return champ.put(owner, writer, raw, existing, value, tid)
                .thenApply(h -> new InodeFileSystem(existing.isPresent() ? inodeCount : inodeCount + 1, champ, storage));
    }

    private CompletableFuture<InodeFileSystem> remove(PublicKeyHash owner,
                                                      SigningPrivateKeyAndPublicHash writer,
                                                      Inode key,
                                                      Optional<DirectoryInode> existing,
                                                      TransactionId tid) {
        byte[] raw = key.serialize();
        return champ.remove(owner, writer, raw, existing, tid)
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
        Inode rootKey = new Inode(0, elements[0]);
        return getOrMkdir(owner, writer, Optional.empty(), rootKey, tid)
                .thenCompose(p -> p.left.addCapRecurse(owner, writer, rootKey, p.right, tail(elements), cap, tid));
    }

    private CompletableFuture<Pair<InodeFileSystem, DirectoryInode>> getOrMkdir(PublicKeyHash owner,
                                                                                SigningPrivateKeyAndPublicHash writer,
                                                                                Optional<Pair<Inode, DirectoryInode>> parent,
                                                                                Inode childDirKey,
                                                                                TransactionId tid) {
        return getValue(childDirKey).thenCompose(opt -> {
            if (opt.isPresent())
                return Futures.of(new Pair<>(this, opt.get()));
            DirectoryInode empty = DirectoryInode.empty(champ.writeHasher, champ.bitWidth, champ.keyHasher, storage);
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
                                .thenCompose(childOpt -> addCapRecurse(owner, writer, childCapOpt.get().inode,
                                        childOpt.get(), tail(remainingPath), cap, tid));
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
        Optional<AbsoluteCapability> startCap = Optional.empty(); // the root is never published
        InodeCap start = new InodeCap(new Inode(0, elements[0]), startCap);
        return getByPathRecurse(start, tail(elements));
    }

    public CompletableFuture<List<InodeCap>> listDirectory(String path) {
        String canonPath = TrieNode.canonicalise(path);
        String[] elements = canonPath.split("/");
        Optional<AbsoluteCapability> startCap = Optional.empty(); // the root is never published
        InodeCap start = new InodeCap(new Inode(0, elements[0]), startCap);
        return listDirectoryRecurse(start, tail(elements));
    }

    private CompletableFuture<List<InodeCap>> listDirectoryRecurse(InodeCap current, String[] elements) {
        return getValue(current.inode)
                .thenCompose(dir -> {
                    if (dir.isEmpty())
                        return Futures.of(Collections.emptyList());
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

    public static CompletableFuture<InodeFileSystem> createEmpty(PublicKeyHash owner,
                                                           SigningPrivateKeyAndPublicHash writer,
                                                           ContentAddressedStorage storage,
                                                           Hasher hasher,
                                                           TransactionId tid) {
        Function<ByteArrayWrapper, CompletableFuture<byte[]>> keyHasher = b -> hasher.sha256(b.data);
        return ChampWrapper.create(owner, writer, keyHasher, tid, storage, hasher,
                c -> DirectoryInode.fromCbor(c, hasher, ChampWrapper.BIT_WIDTH, keyHasher, storage))
                .thenApply(cw -> new InodeFileSystem(0, cw, storage));
    }

    private static String[] tail(String[] in) {
        return Arrays.copyOfRange(in, Math.min(1, in.length), in.length);
    }
}