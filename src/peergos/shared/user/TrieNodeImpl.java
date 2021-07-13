package peergos.shared.user;

import jsinterop.annotations.*;
import peergos.shared.NetworkAccess;
import peergos.shared.crypto.hash.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.Futures;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import java.util.stream.Collectors;

@JsType
public class TrieNodeImpl implements TrieNode {
	private static final Logger LOG = Logger.getGlobal();
    private final Map<String, TrieNode> children;
    private final Optional<EntryPoint> value;

    @JsConstructor
    private TrieNodeImpl(Map<String, TrieNode> children, Optional<EntryPoint> value) {
        this.children = Collections.unmodifiableMap(children);
        this.value = value;
    }

    private CompletableFuture<Optional<FileWrapper>> getAnyValidParentOfAChild(Hasher hasher, NetworkAccess network) {
        return Futures.findFirst(children.values(),
                        n -> n.getByPath("", hasher, network)
                                .thenCompose(child -> child.map(c -> c
                                        .retrieveParent(network)
                                        .thenApply(opt -> opt.map(f -> f.withTrieNode(this))))
                                        .orElseGet(() -> Futures.of(Optional.empty())))
                                .exceptionally(t -> Futures.logAndReturn(t, Optional.empty())));
    }

    @Override
    public CompletableFuture<Optional<FileWrapper>> getByPath(String path, Hasher hasher, NetworkAccess network) {
        FileProperties.ensureValidPath(path);
        LOG.info("GetByPath: " + path);
        String finalPath = TrieNode.canonicalise(path);
        if (finalPath.length() == 0) {
            if (! value.isPresent()) { // find a valid child entry and traverse parent links
                return getAnyValidParentOfAChild(hasher, network);
            }
            return network.retrieveEntryPoint(value.get())
                    .thenCompose(opt -> {
                        if (opt.isPresent()) {
                            if (opt.get().isWritable())
                                return opt.get().getAnyLinkPointer(network)
                                        .thenApply(linkOpt -> opt.map(f -> f.withLinkPointer(linkOpt)));
                            // there may be children which are writable directly if this dir is read only
                            return Futures.of(opt.map(f -> f.withTrieNode(this)));
                        }
                        return getAnyValidParentOfAChild(hasher, network);
                    });
        }
        String[] elements = finalPath.split("/");
        // There may be an entry point further down the tree, but it will have <= permission than this one, unless this is read only
        if (value.isPresent() && (value.get().pointer.isWritable() || !children.containsKey(elements[0])))
            return network.retrieveEntryPoint(value.get())
                    .thenCompose(dir -> dir.get().getDescendentByPath(finalPath, hasher, network));
        if (!children.containsKey(elements[0]))
            return CompletableFuture.completedFuture(Optional.empty());
        return children.get(elements[0]).getByPath(finalPath.substring(elements[0].length()), hasher, network);
    }

    private CompletableFuture<Set<FileWrapper>> indirectlyRetrieveChildren(Hasher hasher, NetworkAccess network) {
        Set<CompletableFuture<Optional<FileWrapper>>> kids = children.values().stream()
                .map(t -> t.getByPath("", hasher, network)).collect(Collectors.toSet());
        return Futures.combineAll(kids)
                .thenApply(set -> set.stream()
                        .filter(opt -> opt.isPresent())
                        .map(opt -> opt.get())
                        .collect(Collectors.toSet()));
    }

    @Override
    @JsIgnore
    public CompletableFuture<Optional<FileWrapper>> getByPath(String path, Snapshot version, Hasher hasher, NetworkAccess network) {
        FileProperties.ensureValidPath(path);
        LOG.info("GetByPath: " + path);
        String finalPath = TrieNode.canonicalise(path);
        if (finalPath.length() == 0) {
            if (! value.isPresent()) { // find a valid child entry and traverse parent links
                return getAnyValidParentOfAChild(hasher, network);
            }
            return network.getFile(value.get(), version)
                    .thenCompose(opt -> {
                        if (opt.isPresent()) {
                            if (opt.get().isWritable())
                                return opt.get().getAnyLinkPointer(network)
                                        .thenApply(linkOpt -> opt.map(f -> f.withLinkPointer(linkOpt)));
                            // there may be children which are writable directly if this dir is read only
                            return Futures.of(opt.map(f -> f.withTrieNode(this)));
                        }
                        return getAnyValidParentOfAChild(hasher, network);
                    });
        }
        String[] elements = finalPath.split("/");
        // There may be an entry point further down the tree, but it will have <= permission than this one, unless this is read only
        if (value.isPresent() && (value.get().pointer.isWritable() || !children.containsKey(elements[0])))
            return network.getFile(version, value.get().pointer, Optional.empty(), value.get().ownerName)
                    .thenCompose(dir -> dir.get().getDescendentByPath(finalPath, hasher, network));
        if (!children.containsKey(elements[0]))
            return CompletableFuture.completedFuture(Optional.empty());
        return children.get(elements[0]).getByPath(finalPath.substring(elements[0].length()), version, hasher, network);
    }

    @Override
    public CompletableFuture<Set<FileWrapper>> getChildren(String path, Hasher hasher, NetworkAccess network) {
        FileProperties.ensureValidPath(path);
        String trimmedPath = TrieNode.canonicalise(path);
        if (trimmedPath.length() == 0) {
            if (! value.isPresent()) { // find a child entry and traverse parent links
                return indirectlyRetrieveChildren(hasher, network);
            }
            return network.retrieveEntryPoint(value.get())
                    .thenCompose(dir -> {
                        if (dir.isPresent())
                            return dir.get().getChildren(hasher, network)
                                    .thenCompose(kids -> {
                                        if (dir.get().isWritable())
                                            return Futures.of(kids);
                                        Set<CompletableFuture<FileWrapper>> futures = kids.stream()
                                                .map(child -> children.containsKey(child.getName()) ?
                                                        children.get(child.getName())
                                                                .getByPath("", hasher, network)
                                                                .thenApply(fopt -> fopt.get()) :
                                                        Futures.of(child))
                                                .collect(Collectors.toSet());
                                        return Futures.combineAll(futures);
                                    });
                        return indirectlyRetrieveChildren(hasher, network);
                    });
        }
        String[] elements = trimmedPath.split("/");
        if (!children.containsKey(elements[0]))
            return network.retrieveEntryPoint(value.get())
                    .thenCompose(dir -> dir.get().getDescendentByPath(trimmedPath, hasher, network)
                            .thenCompose(parent -> parent.map(p -> p.getChildren(hasher, network))
                                    .orElseGet(() -> Futures.of(Collections.emptySet()))));
        return children.get(elements[0]).getChildren(trimmedPath.substring(elements[0].length()), hasher, network);
    }

    @Override
    @JsIgnore
    public CompletableFuture<Set<FileWrapper>> getChildren(String path, Hasher hasher, Snapshot version, NetworkAccess network) {
        FileProperties.ensureValidPath(path);
        String trimmedPath = TrieNode.canonicalise(path);
        if (trimmedPath.length() == 0) {
            if (! value.isPresent()) { // find a child entry and traverse parent links
                return indirectlyRetrieveChildren(hasher, network);
            }
            return network.getFile(value.get(), version)
                    .thenCompose(dir -> {
                        if (dir.isPresent())
                            return dir.get().getChildren(version, hasher, network)
                                    .thenCompose(kids -> {
                                        if (dir.get().isWritable())
                                            return Futures.of(kids);
                                        Set<CompletableFuture<FileWrapper>> futures = kids.stream()
                                                .map(child -> children.containsKey(child.getName()) ?
                                                        children.get(child.getName())
                                                                .getByPath("", version, hasher, network)
                                                                .thenApply(fopt -> fopt.get()) :
                                                        Futures.of(child))
                                                .collect(Collectors.toSet());
                                        return Futures.combineAll(futures);
                                    });
                        return indirectlyRetrieveChildren(hasher, network);
                    });
        }
        String[] elements = trimmedPath.split("/");
        if (!children.containsKey(elements[0]))
            return network.getFile(value.get(), version)
                    .thenCompose(dir -> dir.get().getDescendentByPath(trimmedPath, hasher, network)
                            .thenCompose(parent -> parent.map(p -> p.getChildren(version, hasher, network))
                                    .orElseGet(() -> Futures.of(Collections.emptySet()))));
        return children.get(elements[0]).getChildren(trimmedPath.substring(elements[0].length()), hasher, version, network);
    }

    @Override
    public Set<String> getChildNames() {
        return children.keySet();
    }

    @Override
    public TrieNodeImpl put(String path, EntryPoint e) {
        FileProperties.ensureValidPath(path);
        LOG.info("Entrie.put(" + path + ")");
        path = TrieNode.canonicalise(path);
        if (path.length() == 0) {
            return new TrieNodeImpl(children, Optional.of(e));
        }
        String[] elements = path.split("/");
        TrieNode existing = children.getOrDefault(elements[0], TrieNodeImpl.empty());
        TrieNode newChild = existing.put(path.substring(elements[0].length()), e);

        HashMap<String, TrieNode> newChildren = new HashMap<>(children);
        newChildren.put(elements[0], newChild);
        return new TrieNodeImpl(newChildren, value);
    }

    @Override
    public TrieNode putNode(String path, TrieNode t) {
        FileProperties.ensureValidPath(path);
        LOG.info("Entrie.put(" + path + ")");
        path = TrieNode.canonicalise(path);
        if (path.length() == 0) {
            return t;
        }
        String[] elements = path.split("/");
        TrieNode existing = children.getOrDefault(elements[0], TrieNodeImpl.empty());
        String subPath = path.substring(elements[0].length());
        TrieNode newChild = subPath.isEmpty() ? t : existing.putNode(subPath, t);

        HashMap<String, TrieNode> newChildren = new HashMap<>(children);
        newChildren.put(elements[0], newChild);
        return new TrieNodeImpl(newChildren, value);
    }

    @Override
    public TrieNodeImpl removeEntry(String path) {
        LOG.info("Entrie.rm(" + path + ")");
        path = TrieNode.canonicalise(path);
        if (path.length() == 0) {
            return new TrieNodeImpl(children, Optional.empty());
        }
        String[] elements = path.split("/");
        TrieNode existing = children.getOrDefault(elements[0], TrieNodeImpl.empty());
        TrieNode newChild = existing.removeEntry(path.substring(elements[0].length()));

        HashMap<String, TrieNode> newChildren = new HashMap<>(children);
        if (newChild.isEmpty())
            newChildren.remove(elements[0]);
        else
            newChildren.put(elements[0], newChild);
        return new TrieNodeImpl(newChildren, value);
    }

    @Override
    public Collection<TrieNode> getChildNodes() {
        return children.values();
    }

    @Override
    public TrieNode getChildNode(String name) {
        return children.get(name);
    }

    public static TrieNodeImpl empty() {
        return new TrieNodeImpl(Collections.emptyMap(), Optional.empty());
    }

    @Override
    public boolean isEmpty() {
        return children.size() == 0 && !value.isPresent();
    }
}
