package peergos.shared.user;

import jsinterop.annotations.JsConstructor;
import jsinterop.annotations.JsType;
import peergos.shared.NetworkAccess;
import peergos.shared.user.fs.FileWrapper;
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

    @Override
    public CompletableFuture<Optional<FileWrapper>> getByPath(String path, NetworkAccess network) {
        LOG.info("GetByPath: " + path);
        String finalPath = TrieNode.canonicalise(path);
        if (finalPath.length() == 0) {
            if (! value.isPresent()) { // find a child entry and traverse parent links
                return children.values().stream()
                        .findAny()
                        .get()
                        .getByPath("", network)
                        .thenCompose(child -> child.get()
                                .retrieveParent(network)
                                .thenApply(opt -> opt.map(f -> f.withTrieNode(this))));
            }
            return network.retrieveEntryPoint(value.get());
        }
        String[] elements = finalPath.split("/");
        // There may be an entry point further down the tree, but it will have <= permission than this one
        if (value.isPresent())
            return network.retrieveEntryPoint(value.get())
                    .thenCompose(dir -> dir.get().getDescendentByPath(finalPath, network));
        if (!children.containsKey(elements[0]))
            return CompletableFuture.completedFuture(Optional.empty());
        return children.get(elements[0]).getByPath(finalPath.substring(elements[0].length()), network);
    }

    @Override
    public CompletableFuture<Set<FileWrapper>> getChildren(String path, NetworkAccess network) {
        String trimmedPath = TrieNode.canonicalise(path);
        if (trimmedPath.length() == 0) {
            if (!value.isPresent()) { // find a child entry and traverse parent links
                Set<CompletableFuture<Optional<FileWrapper>>> kids = children.values().stream()
                        .map(t -> t.getByPath("", network)).collect(Collectors.toSet());
                return Futures.combineAll(kids)
                        .thenApply(set -> set.stream()
                                .filter(opt -> opt.isPresent())
                                .map(opt -> opt.get())
                                .collect(Collectors.toSet()));
            }
            return network.retrieveEntryPoint(value.get())
                    .thenCompose(dir -> dir.get().getChildren(network));
        }
        String[] elements = trimmedPath.split("/");
        if (!children.containsKey(elements[0]))
            return network.retrieveEntryPoint(value.get())
                    .thenCompose(dir -> dir.get().getDescendentByPath(trimmedPath, network)
                            .thenCompose(parent -> parent.get().getChildren(network)));
        return children.get(elements[0]).getChildren(trimmedPath.substring(elements[0].length()), network);
    }

    @Override
    public Set<String> getChildNames() {
        return children.keySet();
    }

    @Override
    public TrieNodeImpl put(String path, EntryPoint e) {
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
        LOG.info("Entrie.put(" + path + ")");
        path = TrieNode.canonicalise(path);
        if (path.length() == 0) {
            return t;
        }
        String[] elements = path.split("/");
        TrieNode existing = children.getOrDefault(elements[0], TrieNodeImpl.empty());
        TrieNode newChild = existing.putNode(path.substring(elements[0].length()), t);

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

    public static TrieNodeImpl empty() {
        return new TrieNodeImpl(Collections.emptyMap(), Optional.empty());
    }

    @Override
    public boolean isEmpty() {
        return children.size() == 0 && !value.isPresent();
    }
}
