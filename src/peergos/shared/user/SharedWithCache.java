package peergos.shared.user;

import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

/** Who we've shared each file with is stored in a parallel directory tree under the CACHE_BASE dir.
 *  A serialized SharedWithState for all the children of a directory is stored at
 *  CACHE_BASE/$path-to-dir/sharedWith.cbor
 */
public class SharedWithCache {

    public enum Access { READ, WRITE }

    private static final String DIR_CACHE_FILENAME = "sharedWith.cbor";
    private static final String CACHE_BASE_NAME = "outbound";
    private static final Path CACHE_BASE = Paths.get(CapabilityStore.CAPABILITY_CACHE_DIR, CACHE_BASE_NAME);

    private final FileWrapper base;
    private final String ourname;
    private final NetworkAccess network;
    private final Crypto crypto;

    public SharedWithCache(FileWrapper base,
                           String ourname,
                           NetworkAccess network,
                           Crypto crypto) {
        this.base = base;
        this.ourname = ourname;
        this.network = network;
        this.crypto = crypto;
    }

    private static Path canonicalise(Path p) {
        return p.isAbsolute() ? p : Paths.get("/").resolve(p);
    }

    private static Path toRelative(Path p) {
        return p.isAbsolute() ? Paths.get(p.toString().substring(1)) : p;
    }

    private static Path cacheBase(String username) {
        return Paths.get("/" + username).resolve(CACHE_BASE);
    }

    private CompletableFuture<Optional<SharedWithState>> retrieve(Path dir) {
        return retrieveWithFile(dir).thenApply(opt -> opt.map(p -> p.right));
    }

    private CompletableFuture<Optional<Pair<FileWrapper, SharedWithState>>> retrieveWithFile(Path dir) {
        return base.getDescendentByPath(toRelative(dir).resolve(DIR_CACHE_FILENAME).toString(), crypto.hasher, network)
                .thenCompose(opt -> opt.isEmpty() ?
                        Futures.of(Optional.empty()) :
                        parseCacheFile(opt.get(), network, crypto)
                                .thenApply(s -> new Pair<>(opt.get(), s))
                                .thenApply(Optional::of)
                );
    }

    private static CompletableFuture<Snapshot> staticAddSharedWith(FileWrapper base,
                                                                   Access access,
                                                                   Path toFile,
                                                                   Set<String> names,
                                                                   NetworkAccess network,
                                                                   Crypto crypto,
                                                                   Snapshot in,
                                                                   Committer committer) {
        return base.getUpdated(in, network)
                .thenCompose(updateed -> retrieveWithFileOrCreate(updateed, toFile.getParent(), network, crypto, in, committer))
                .thenCompose(p -> {
                    FileWrapper source = p.left;
                    SharedWithState current = p.right;
                    SharedWithState updated = current.add(access, getFilename(toFile), names);
                    byte[] raw = updated.serialize();
                    return source.overwriteFile(AsyncReader.build(raw), raw.length, network, crypto, x -> {}, source.version, committer);
                });
    }

    private static CompletableFuture<Boolean> buildSharedWithCache(TrieNode root, String ourname, NetworkAccess network, Crypto crypto) {
        return root.getByPath(Paths.get(ourname, CapabilityStore.CAPABILITY_CACHE_DIR).toString(), crypto.hasher, network)
                .thenCompose(cacheDirOpt -> root.getByPath(Paths.get("/" + ourname + "/" + UserContext.SHARED_DIR_NAME).toString(), crypto.hasher, network)
                        .thenCompose(shared -> shared.get().getChildren(crypto.hasher, network))
                        .thenCompose(children ->
                                Futures.reduceAll(children,
                                        true,
                                        (x, friendDirectory) ->
                                                friendDirectory.getUpdated(network)
                                                        .thenCompose(updatedFriendDir -> CapabilityStore.loadReadOnlyLinks(cacheDirOpt.get(), updatedFriendDir,
                                                                ourname, network, crypto, false))
                                                        .thenCompose(readCaps ->
                                                                network.synchronizer.applyComplexUpdate(friendDirectory.owner(),
                                                                        friendDirectory.signingPair(),
                                                                        (s, c) -> Futures.reduceAll(readCaps.getRetrievedCapabilities(),
                                                                                s,
                                                                                (v, rc) -> staticAddSharedWith(cacheDirOpt.get(), Access.READ, Paths.get(rc.path), Collections.singleton(friendDirectory.getName()), network, crypto, v, c),
                                                                                (a, b) -> b)
                                                                                .thenCompose(s2 -> friendDirectory.getUpdated(network)
                                                                                        .thenCompose(updatedFriendDir -> CapabilityStore.loadWriteableLinks(cacheDirOpt.get(), updatedFriendDir,
                                                                                                ourname, network, crypto, false))
                                                                                        .thenCompose(writeCaps ->
                                                                                                Futures.reduceAll(writeCaps.getRetrievedCapabilities(), s2,
                                                                                                        (v, rc) -> staticAddSharedWith(cacheDirOpt.get(), Access.WRITE, Paths.get(rc.path), Collections.singleton(friendDirectory.getName()), network, crypto, v, c),
                                                                                                        (a, b) -> b)
                                                                                        ))))
                                                        .thenApply(y -> true),
                                        (a, b) -> a && b)));
    }

    private static CompletableFuture<SharedWithCache> initializeCache(TrieNode root, String username, NetworkAccess network, Crypto crypto) {
        return root.getByPath(Paths.get(username).toString(), crypto.hasher, network)
                .thenCompose(userRoot -> network.synchronizer.applyComplexUpdate(userRoot.get().owner(), userRoot.get().signingPair(),
                        (s, c) -> getOrMkdir(userRoot.get(), CapabilityStore.CAPABILITY_CACHE_DIR, network, crypto, s, c)
                                .thenApply(f -> f.version))
                        .thenCompose(s -> userRoot.get().getUpdated(s, network)
                                .thenCompose(home -> home.getChild(CapabilityStore.CAPABILITY_CACHE_DIR, crypto.hasher, network))
                                .thenCompose(cacheRootOpt -> cacheRootOpt.get().mkdir(CACHE_BASE_NAME, network, true, crypto)))
                        .thenCompose(x -> buildSharedWithCache(root, username, network, crypto)) // build from outbound cap files
                        .thenCompose(x -> root.getByPath(cacheBase(username).toString(), crypto.hasher, network)
                                .thenApply(Optional::get)
                                .thenApply(f -> new SharedWithCache(f, username, network, crypto))));
    }

    public static CompletableFuture<SharedWithCache> initOrBuild(TrieNode root, String username, NetworkAccess network, Crypto crypto) {
        return root.getByPath(cacheBase(username).toString(), crypto.hasher, network)
                .thenCompose(opt -> {
                    if (opt.isPresent())
                        return Futures.of(new SharedWithCache(opt.get(), username, network, crypto));
                    return initializeCache(root, username, network, crypto);
                });
    }

    private static CompletableFuture<FileWrapper> getOrMkdir(FileWrapper parent, String dirName, NetworkAccess network, Crypto crypto, Snapshot s, Committer c) {
        return parent.getChild(dirName, crypto.hasher, network)
                .thenCompose(opt -> opt.isPresent() ?
                        Futures.of(opt.get()) :
                        parent.mkdir(dirName, Optional.empty(), Optional.empty(), Optional.empty(), true, network, crypto, s, c)
                                .thenCompose(s2 -> parent.getUpdated(s2, network)
                                        .thenCompose(p -> p.getChild(dirName, crypto.hasher, network)))
                                .thenApply(Optional::get));
    }

    private static CompletableFuture<FileWrapper> getOrMkdirs(FileWrapper parent, List<String> remaining, NetworkAccess network, Crypto crypto, Snapshot s, Committer c) {
        if (remaining.isEmpty())
            return Futures.of(parent);
        return getOrMkdir(parent, remaining.get(0), network, crypto, s, c)
                .thenCompose(child -> getOrMkdirs(child, remaining.subList(1, remaining.size()), network, crypto, child.version, c));
    }

    public CompletableFuture<SharedWithState> getDirSharingState(Path dir) {
        if (this.ourname == null) {
            return Futures.of(SharedWithState.empty());
        }
        return base.getDescendentByPath(dir.resolve(DIR_CACHE_FILENAME).toString(), crypto.hasher, network)
                .thenCompose(fopt -> fopt.map(f -> parseCacheFile(f, network, crypto)).orElse(Futures.of(SharedWithState.empty())));
    }

    private static CompletableFuture<Pair<FileWrapper, SharedWithState>> retrieveWithFileOrCreate(FileWrapper base,
                                                                                                  Path dir,
                                                                                                  NetworkAccess network,
                                                                                                  Crypto crypto,
                                                                                                  Snapshot in,
                                                                                                  Committer committer) {
        return getOrMkdirs(base, toList(dir), network, crypto, in, committer)
                .thenCompose(parent -> parent.getChild(DIR_CACHE_FILENAME, crypto.hasher, network)
                        .thenCompose(fopt -> {
                            if (fopt.isPresent())
                                return parseCacheFile(fopt.get(), network, crypto)
                                        .thenApply(c -> new Pair<>(fopt.get(), c));
                            SharedWithState empty = SharedWithState.empty();
                            byte[] raw = empty.serialize();
                            // upload or replace file
                            return parent.uploadFileSection(parent.version, committer, DIR_CACHE_FILENAME, AsyncReader.build(raw), false, 0, raw.length,
                                    Optional.empty(), true, true, network, crypto, x -> {}, crypto.random.randomBytes(32))
                                    .thenCompose(s -> parent.getUpdated(s, network)
                                            .thenCompose(updatedParent -> updatedParent.getChild(DIR_CACHE_FILENAME, crypto.hasher, network)))
                                    .thenApply(copt -> new Pair<>(copt.get(), empty));
                        }));
    }

    private static List<String> toList(Path p) {
        return Arrays.asList(toRelative(p).toString().split("/"));
    }

    private static CompletableFuture<SharedWithState> parseCacheFile(FileWrapper cache, NetworkAccess network, Crypto crypto) {
        return cache.getInputStream(cache.version.get(cache.writer()).props, network, crypto, x -> {})
                .thenCompose(in -> Serialize.readFully(in, cache.getSize()))
                .thenApply(CborObject::fromByteArray)
                .thenApply(SharedWithState::fromCbor);
    }

    private static String getFilename(Path p) {
        return p.getName(p.getNameCount() - 1).toString();
    }

    public CompletableFuture<Map<Path, SharedWithState>> getAllDescendantShares(Path start, Snapshot s) {
        return base.getUpdated(base.version.mergeAndOverwriteWith(s), network)
                .thenCompose(freshBase -> freshBase.getDescendentByPath(toRelative(start.getParent()).toString(), crypto.hasher, network))
                .thenCompose(opt -> {
                    if (opt.isEmpty())
                        return Futures.of(Collections.emptyMap());
                    FileWrapper parent = opt.get();
                    String filename = getFilename(start);
                    return parent.getChild(DIR_CACHE_FILENAME, crypto.hasher, network)
                            .thenCompose(fopt -> fopt.isEmpty() ?
                                    Futures.of(Collections.<Path, SharedWithState>emptyMap()) :
                                    parseCacheFile(fopt.get(), network, crypto)
                                            .thenApply(c -> c.filter(filename)
                                                    .map(r -> Collections.singletonMap(start.getParent(), r))
                                                    .orElse(Collections.emptyMap()))
                            ).thenCompose(m -> parent.getChild(filename, crypto.hasher, network)
                                    .thenCompose(copt -> copt.isEmpty() ?
                                            Futures.of(m) :
                                            getAllDescendantSharesRecurse(copt.get(), start)
                                                    .thenApply(d -> merge(d, m))));
                });
    }

    private <K, V> Map<K, V> merge(Map<K, V> a, Map<K, V> b) {
        HashMap<K, V> res = new HashMap<>(a);
        res.putAll(b); // no key conflicts
        return res;
    }

    public CompletableFuture<Map<Path, SharedWithState>> getAllDescendantSharesRecurse(FileWrapper f, Path toUs) {
        if (! f.isDirectory()) {
            if (! f.getName().equals(DIR_CACHE_FILENAME))
                throw new IllegalStateException("Invalid shared with cache!");
            return parseCacheFile(f, network, crypto)
                    .thenApply(c -> Collections.singletonMap(toUs.getParent(), c));
        }
        return f.getChildren(crypto.hasher, network)
                .thenCompose(children -> Futures.combineAll(children.stream()
                        .map(c -> getAllDescendantSharesRecurse(c, toUs.resolve(c.getName())))
                        .collect(Collectors.toList())))
                .thenApply(s -> s.stream()
                        .flatMap(m -> m.entrySet().stream())
                        .collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue())));
    }

    public CompletableFuture<Map<Path, Set<String>>> getAllReadShares(Path start, Snapshot s) {
        return getAllDescendantShares(start, s)
                .thenApply(m -> m.entrySet().stream()
                        .flatMap(e -> e.getValue().readShares().entrySet()
                                .stream()
                                .map(e2 -> new Pair<>(e.getKey().resolve(e2.getKey()), e2.getValue())))
                        .collect(Collectors.toMap(p -> p.left, p -> p.right)));
    }

    public CompletableFuture<Map<Path, Set<String>>> getAllWriteShares(Path start, Snapshot s) {
        return getAllDescendantShares(start, s)
                .thenApply(m -> m.entrySet().stream()
                        .flatMap(e -> e.getValue().writeShares().entrySet()
                                .stream()
                                .map(e2 -> new Pair<>(e.getKey().resolve(e2.getKey()), e2.getValue())))
                        .collect(Collectors.toMap(p -> p.left, p -> p.right)));
    }

    public CompletableFuture<FileSharedWithState> getSharedWith(Path p) {
        return retrieve(p.getParent())
                .thenApply(opt -> opt.map(s -> s.get(getFilename(p))).orElse(FileSharedWithState.EMPTY));
    }

    public CompletableFuture<Snapshot> applyAndCommit(Path toFile, Function<SharedWithState, SharedWithState> transform, Snapshot in, Committer committer) {
        return base.getUpdated(in, network)
                .thenCompose(updated -> retrieveWithFileOrCreate(updated, toFile.getParent(), network, crypto, in, committer))
                .thenCompose(p -> {
                    FileWrapper source = p.left;
                    SharedWithState current = p.right;
                    SharedWithState updated = transform.apply(current);
                    byte[] raw = updated.serialize();
                    return source.overwriteFile(AsyncReader.build(raw), raw.length, network, crypto, x -> {}, source.version, committer);
                });
    }

    public CompletableFuture<Snapshot> rename(Path initial, Path after, Snapshot in, Committer committer) {
        if (! initial.getParent().equals(after.getParent()))
            throw new IllegalStateException("Not a valid rename!");
        String initialFilename = getFilename(initial);
        String newFilename = getFilename(after);
        return getSharedWith(initial)
                .thenCompose(sharees -> applyAndCommit(after, current ->
                        current.add(Access.READ, newFilename, sharees.readAccess)
                                .add(Access.WRITE, newFilename, sharees.writeAccess)
                                .clear(initialFilename), in, committer));
    }

    public CompletableFuture<Snapshot> addSharedWith(Access access, Path p, Set<String> names, Snapshot in, Committer committer) {
        return applyAndCommit(p, current -> current.add(access, getFilename(p), names), in, committer);
    }

    public CompletableFuture<Snapshot> clearSharedWith(Path p, Snapshot in, Committer committer) {
        return applyAndCommit(p, current -> current.clear(getFilename(p)), in, committer);
    }

    public CompletableFuture<Snapshot> removeSharedWith(Access access, Path p, Set<String> names, Snapshot in, Committer committer) {
        return applyAndCommit(p, current -> current.remove(access, getFilename(p), names), in, committer);
    }
}
