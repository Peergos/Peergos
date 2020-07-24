package peergos.shared.user;

import jsinterop.annotations.*;
import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

public class SharedWithCache {

    public enum Access { READ, WRITE }

    private static final String DIR_CACHE_FILENAME = "sharedWith.cbor";
    private static final String CACHE_BASE_NAME = "outbound";
    private static final Path CACHE_BASE = Paths.get(CapabilityStore.CAPABILITY_CACHE_DIR, CACHE_BASE_NAME);

    public static class FileSharedWithState {
        public static final FileSharedWithState EMPTY = new FileSharedWithState(Collections.emptySet(), Collections.emptySet());
        public final Set<String> readAccess, writeAccess;

        public FileSharedWithState(Set<String> readAccess, Set<String> writeAccess) {
            this.readAccess = readAccess;
            this.writeAccess = writeAccess;
        }

        public Set<String> get(Access type) {
            if (type == Access.READ)
                return readAccess;
            return writeAccess;
        }
    }

    /** Holds the sharing state for all the children of a directory
     *
     */
    public static class SharedWithState implements Cborable {
        public static final SharedWithState EMPTY = new SharedWithState(Collections.emptyMap(), Collections.emptyMap());
        private final Map<String, Set<String>> readShares;
        private final Map<String, Set<String>> writeShares;

        public SharedWithState(Map<String, Set<String>> readShares, Map<String, Set<String>> writeShares) {
            this.readShares = readShares;
            this.writeShares = writeShares;
        }

        public static SharedWithState empty() {
            return new SharedWithState(new HashMap<>(), new HashMap<>());
        }

        public FileSharedWithState get(String filename) {
            return new FileSharedWithState(
                    readShares.getOrDefault(filename, Collections.emptySet()),
                    writeShares.getOrDefault(filename, Collections.emptySet()));
        }

        public Optional<SharedWithState> filter(String childName) {
            if (! readShares.containsKey(childName) && ! writeShares.containsKey(childName))
                return Optional.empty();
            Map<String, Set<String>> newReads = new HashMap<>();
            for (Map.Entry<String, Set<String>> e : readShares.entrySet()) {
                if (e.getKey().equals(childName))
                    newReads.put(e.getKey(), new HashSet<>(e.getValue()));
            }
            Map<String, Set<String>> newWrites = new HashMap<>();
            for (Map.Entry<String, Set<String>> e : writeShares.entrySet()) {
                if (e.getKey().equals(childName))
                    newWrites.put(e.getKey(), new HashSet<>(e.getValue()));
            }

            return Optional.of(new SharedWithState(newReads, newWrites));
        }

        public SharedWithState add(Access access, String filename, Set<String> names) {
            Map<String, Set<String>> newReads = new HashMap<>();
            for (Map.Entry<String, Set<String>> e : readShares.entrySet()) {
                newReads.put(e.getKey(), new HashSet<>(e.getValue()));
            }
            Map<String, Set<String>> newWrites = new HashMap<>();
            for (Map.Entry<String, Set<String>> e : writeShares.entrySet()) {
                newWrites.put(e.getKey(), new HashSet<>(e.getValue()));
            }

            if (access == Access.READ) {
                newReads.putIfAbsent(filename, new HashSet<>());
                newReads.get(filename).addAll(names);
            } else if (access == Access.WRITE) {
                newWrites.putIfAbsent(filename, new HashSet<>());
                newWrites.get(filename).addAll(names);
            }

            return new SharedWithState(newReads, newWrites);
        }

        public SharedWithState remove(Access access, String filename, Set<String> names) {
            Map<String, Set<String>> newReads = new HashMap<>();
            for (Map.Entry<String, Set<String>> e : readShares.entrySet()) {
                newReads.put(e.getKey(), new HashSet<>(e.getValue()));
            }
            Map<String, Set<String>> newWrites = new HashMap<>();
            for (Map.Entry<String, Set<String>> e : writeShares.entrySet()) {
                newWrites.put(e.getKey(), new HashSet<>(e.getValue()));
            }

            Set<String> val = access == Access.READ ? newReads.get(filename) : newWrites.get(filename);
            if (val != null)
                val.removeAll(names);

            return new SharedWithState(newReads, newWrites);
        }

        public SharedWithState clear(String filename) {
            Map<String, Set<String>> newReads = new HashMap<>();
            for (Map.Entry<String, Set<String>> e : readShares.entrySet()) {
                newReads.put(e.getKey(), new HashSet<>(e.getValue()));
            }
            Map<String, Set<String>> newWrites = new HashMap<>();
            for (Map.Entry<String, Set<String>> e : writeShares.entrySet()) {
                newWrites.put(e.getKey(), new HashSet<>(e.getValue()));
            }

            newReads.remove(filename);
            newWrites.remove(filename);

            return new SharedWithState(newReads, newWrites);
        }

        @JsMethod
        public boolean isShared(String filename) {
            return readShares.containsKey(filename) || writeShares.containsKey(filename);
        }

        @Override
        public CborObject toCbor() {
            SortedMap<String, Cborable> state = new TreeMap<>();
            SortedMap<String, Cborable> readState = new TreeMap<>();
            for (Map.Entry<String, Set<String>> e : readShares.entrySet()) {
                readState.put(e.getKey(), new CborObject.CborList(e.getValue().stream().map(CborObject.CborString::new).collect(Collectors.toList())));
            }
            SortedMap<String, Cborable> writeState = new TreeMap<>();
            for (Map.Entry<String, Set<String>> e : writeShares.entrySet()) {
                writeState.put(e.getKey(), new CborObject.CborList(e.getValue().stream().map(CborObject.CborString::new).collect(Collectors.toList())));
            }

            state.put("r", CborObject.CborMap.build(readState));
            state.put("w", CborObject.CborMap.build(writeState));
            return CborObject.CborMap.build(state);
        }

        public static SharedWithState fromCbor(Cborable cbor) {
            if (! (cbor instanceof CborObject.CborMap))
                throw new IllegalStateException("Invalid cbor for SharedWithState!");
            CborObject.CborMap m = (CborObject.CborMap) cbor;
            CborObject.CborMap r = m.get("r", c -> (CborObject.CborMap) c);
            Function<Cborable, String> getString = c -> ((CborObject.CborString) c).value;
            Map<String, Set<String>> readShares = r.getMap(
                    getString,
                    c -> new HashSet<>(((CborObject.CborList)c).map(getString)));

            CborObject.CborMap w = m.get("w", c -> (CborObject.CborMap) c);
            Map<String, Set<String>> writehares = w.getMap(
                    getString,
                    c -> new HashSet<>(((CborObject.CborList)c).map(getString)));

            return new SharedWithState(readShares, writehares);
        }
    }

    private final Function<Path, CompletableFuture<Optional<FileWrapper>>> retriever;
    private final String ourname;
    private final NetworkAccess network;
    private final Crypto crypto;

    public SharedWithCache(Function<Path, CompletableFuture<Optional<FileWrapper>>> retriever,
                           String ourname,
                           NetworkAccess network,
                           Crypto crypto) {
        this.retriever = retriever;
        this.ourname = ourname;
        this.network = network;
        this.crypto = crypto;
    }

    private static Path canonicalise(Path p) {
        return p.isAbsolute() ? p : Paths.get("/").resolve(p);
    }

    private CompletableFuture<Optional<SharedWithState>> retrieve(Path dir) {
        return retrieveWithFile(dir).thenApply(opt -> opt.map(p -> p.right));
    }

    private CompletableFuture<Optional<Pair<FileWrapper, SharedWithState>>> retrieveWithFile(Path dir) {
        return retriever.apply(CACHE_BASE.resolve(dir).resolve(DIR_CACHE_FILENAME))
                .thenCompose(opt -> opt.isEmpty() ?
                        Futures.of(Optional.empty()) :
                        parseCacheFile(opt.get())
                                .thenApply(s -> new Pair<>(opt.get(), s))
                                .thenApply(Optional::of)
                );
    }

    /**
     *
     * @return root of cache
     */
    private CompletableFuture<FileWrapper> initializeCache() {
        return retriever.apply(Paths.get(ourname))
                .thenCompose(userRoot -> getOrMkdir(userRoot.get(), CapabilityStore.CAPABILITY_CACHE_DIR))
                .thenCompose(cacheRoot -> getOrMkdir(cacheRoot, CACHE_BASE_NAME)); //TODO build from outbound cap files
    }

    private CompletableFuture<FileWrapper> getOrMkdir(FileWrapper parent, String dirName) {
        return parent.getChild(dirName, crypto.hasher, network)
                .thenCompose(opt -> opt.isPresent() ?
                        Futures.of(opt.get()) :
                        parent.mkdir(dirName, network, true, crypto)
                                .thenCompose(p -> p.getChild(dirName, crypto.hasher, network))
                                .thenApply(Optional::get));
    }

    private CompletableFuture<FileWrapper> getOrMkdirs(FileWrapper parent, List<String> remaining) {
        if (remaining.isEmpty())
            return Futures.of(parent);
        return getOrMkdir(parent, remaining.get(0))
                .thenCompose(child -> getOrMkdirs(child, remaining.subList(1, remaining.size())));
    }

    private CompletableFuture<Pair<FileWrapper, SharedWithState>> retrieveWithFileOrCreate(Path dir) {
        return retriever.apply(CACHE_BASE)
                .thenCompose(opt -> opt.isEmpty() ?
                        initializeCache() :
                        Futures.of(opt.get())
                ).thenCompose(cacheRoot -> getOrMkdirs(cacheRoot, toList(dir)))
                .thenCompose(parent -> parent.getChild(DIR_CACHE_FILENAME, crypto.hasher, network)
                        .thenCompose(fopt -> {
                            if (fopt.isPresent())
                                return parseCacheFile(fopt.get())
                                        .thenApply(c -> new Pair<>(fopt.get(), c));
                            SharedWithState empty = SharedWithState.empty();
                            byte[] raw = empty.serialize();
                            return parent.uploadOrReplaceFile(DIR_CACHE_FILENAME, AsyncReader.build(raw), raw.length,
                                    network, crypto, x -> {}, crypto.random.randomBytes(32))
                                    .thenCompose(updatedParent -> updatedParent.getChild(DIR_CACHE_FILENAME, crypto.hasher, network))
                                    .thenApply(copt -> new Pair<>(copt.get(), empty));
                        }));
    }

    private List<String> toList(Path p) {
        return Arrays.asList(p.toString().split("/"));
    }

    private CompletableFuture<SharedWithState> parseCacheFile(FileWrapper cache) {
        return cache.getInputStream(network, crypto, x -> {})
                .thenCompose(in -> Serialize.readFully(in, cache.getSize()))
                .thenApply(CborObject::fromByteArray)
                .thenApply(SharedWithState::fromCbor);
    }

    public CompletableFuture<Map<Path, SharedWithState>> getAllDescendantShares(Path start) {
        return retriever.apply(CACHE_BASE.resolve(start.getParent()))
                .thenCompose(opt -> {
                    if (opt.isEmpty())
                        return Futures.of(Collections.emptyMap());
                    FileWrapper parent = opt.get();
                    String filename = start.toFile().getName();
                    return parent.getChild(DIR_CACHE_FILENAME, crypto.hasher, network)
                            .thenCompose(fopt -> fopt.isEmpty() ?
                                    Futures.of(Collections.<Path, SharedWithState>emptyMap()) :
                                    parseCacheFile(fopt.get())
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
            return parseCacheFile(f)
                    .thenApply(c -> Collections.singletonMap(toUs.getParent(), c));
        }
        return f.getChildren(crypto.hasher, network)
                .thenCompose(children -> Futures.combineAll(children.stream()
                        .map(c -> getAllDescendantSharesRecurse(c, toUs))
                        .collect(Collectors.toList())))
                .thenApply(s -> s.stream()
                        .flatMap(m -> m.entrySet().stream())
                        .collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue())));
    }

    public CompletableFuture<Map<Path, Set<String>>> getAllReadShares(Path start) {
        return getAllDescendantShares(start)
                .thenApply(m -> m.entrySet().stream()
                        .flatMap(e -> e.getValue().readShares.entrySet()
                                .stream()
                                .map(e2 -> new Pair<>(e.getKey().resolve(e2.getKey()), e2.getValue())))
                        .collect(Collectors.toMap(p -> p.left, p -> p.right)));
    }

    public CompletableFuture<Map<Path, Set<String>>> getAllWriteShares(Path start) {
        return getAllDescendantShares(start)
                .thenApply(m -> m.entrySet().stream()
                        .flatMap(e -> e.getValue().writeShares.entrySet()
                                .stream()
                                .map(e2 -> new Pair<>(e.getKey().resolve(e2.getKey()), e2.getValue())))
                        .collect(Collectors.toMap(p -> p.left, p -> p.right)));
    }

    public CompletableFuture<FileSharedWithState> getSharedWith(Path p) {
        return retrieve(p.getParent())
                .thenApply(opt -> opt.map(s -> s.get(p.toFile().getName())).orElse(FileSharedWithState.EMPTY));
    }

    public CompletableFuture<Boolean> applyAndCommit(Path toFile, Function<SharedWithState, SharedWithState> transform) {
        return retrieveWithFileOrCreate(toFile.getParent()).thenCompose(p -> {
            FileWrapper source = p.left;
            SharedWithState current = p.right;
            SharedWithState updated = transform.apply(current);
            byte[] raw = updated.serialize();
            return source.overwriteFile(AsyncReader.build(raw), raw.length, network, crypto, x -> {})
                    .thenApply(x -> true);
        });
    }

    public CompletableFuture<Boolean> addSharedWith(Access access, Path p, Set<String> names) {
        return applyAndCommit(p, current -> current.add(access, p.toFile().getName(), names));
    }

    public CompletableFuture<Boolean> clearSharedWith(Path p) {
        return applyAndCommit(p, current -> current.clear(p.toFile().getName()));
    }

    public CompletableFuture<Boolean> removeSharedWith(Access access, Path p, Set<String> names) {
        return applyAndCommit(p, current -> current.remove(access, p.toFile().getName(), names));
    }
}
