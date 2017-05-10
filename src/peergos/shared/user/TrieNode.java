package peergos.shared.user;

import peergos.shared.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

public class TrieNode {
    private final Map<String, TrieNode> children;
    private final Optional<EntryPoint> value;
    private final Map<String, String> pathMappings;

    public TrieNode(Map<String, TrieNode> children, Optional<EntryPoint> value, Map<String, String> pathMappings) {
        this.children = Collections.unmodifiableMap(children);
        this.value = value;
        this.pathMappings = Collections.unmodifiableMap(pathMappings);
    }

    public TrieNode() {
        this(Collections.emptyMap(), Optional.empty(), Collections.emptyMap());
    }

    public CompletableFuture<Optional<FileTreeNode>> getByPath(String path, NetworkAccess network) {
        System.out.println("GetByPath: " + path);
        for (String prefix: pathMappings.keySet()) {
            if (path.startsWith(prefix)) {
                path = pathMappings.get(prefix) + path.substring(prefix.length());
            }
        }
        String finalPath = path.startsWith("/") ? path.substring(1) : path;
        if (finalPath.length() == 0) {
            if (! value.isPresent()) { // find a child entry and traverse parent links
                return children.values().stream().findAny().get()
                        .getByPath("", network)
                        .thenCompose(child -> child.get().retrieveParent(network));
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

    public CompletableFuture<Set<FileTreeNode>> getChildren(String path, NetworkAccess network) {
        String trimmedPath = path.startsWith("/") ? path.substring(1) : path;
        if (trimmedPath.length() == 0) {
            if (!value.isPresent()) { // find a child entry and traverse parent links
                Set<CompletableFuture<Optional<FileTreeNode>>> kids = children.values().stream()
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

    public Set<String> getChildNames() {
        return children.keySet();
    }

    public TrieNode put(String path, EntryPoint e) {
        System.out.println("Entrie.put(" + path + ")");
        if (path.startsWith("/"))
            path = path.substring(1);
        if (path.length() == 0) {
            return new TrieNode(children, Optional.of(e), pathMappings);
        }
        String[] elements = path.split("/");
        TrieNode existing = children.getOrDefault(elements[0], new TrieNode());
        TrieNode newChild = existing.put(path.substring(elements[0].length()), e);

        HashMap<String, TrieNode> newChildren = new HashMap<>(children);
        newChildren.put(elements[0], newChild);
        return new TrieNode(newChildren, value, pathMappings);
    }

    public TrieNode removeEntry(String path) {
        System.out.println("Entrie.rm(" + path + ")");
        for (String prefix: pathMappings.keySet()) {
            if (path.startsWith(prefix)) {
                path = pathMappings.get(prefix) + path.substring(prefix.length());
            }
        }
        if (path.startsWith("/"))
            path = path.substring(1);
        if (path.length() == 0) {
            return new TrieNode(children, Optional.empty(), pathMappings);
        }
        String[] elements = path.split("/");
        TrieNode existing = children.getOrDefault(elements[0], new TrieNode());
        TrieNode newChild = existing.removeEntry(path.substring(elements[0].length()));

        HashMap<String, TrieNode> newChildren = new HashMap<>(children);
        if (newChild.isEmpty())
            newChildren.remove(elements[0]);
        else
            newChildren.put(elements[0], newChild);
        return new TrieNode(newChildren, value, pathMappings);
    }

    public TrieNode addPathMapping(String prefix, String target) {
        Map<String, String> newLinks = new HashMap<>(pathMappings);
        newLinks.put(prefix, target);
        return new TrieNode(children, value, newLinks);
    }

    public boolean isEmpty() {
        return children.size() == 0 && !value.isPresent();
    }

    public TrieNode clear() {
        return new TrieNode();
    }
}
