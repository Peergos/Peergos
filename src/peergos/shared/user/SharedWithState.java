package peergos.shared.user;

import jsinterop.annotations.*;
import peergos.shared.cbor.*;
import peergos.shared.util.*;

import java.time.*;
import java.util.*;
import java.util.function.*;
import java.util.stream.*;

/** Holds the sharing state for all the children of a directory
 *
 */
public class SharedWithState implements Cborable {
    private final Map<String, Set<String>> readShares;
    private final Map<String, Set<String>> writeShares;
    private final Map<String, Set<LinkProperties>> links;

    public SharedWithState(Map<String, Set<String>> readShares,
                           Map<String, Set<String>> writeShares,
                           Map<String, Set<LinkProperties>> links) {
        this.readShares = readShares;
        this.writeShares = writeShares;
        this.links = links;
    }

    public boolean isEmpty() {
        return readShares.isEmpty() && writeShares.isEmpty();
    }

    public static SharedWithState empty() {
        return new SharedWithState(new HashMap<>(), new HashMap<>(), new HashMap<>());
    }

    public Map<String, Set<String>> readShares() {
        return readShares;
    }

    public Map<String, Set<LinkProperties>> links() {
        return links;
    }

    public Map<String, Set<String>> writeShares() {
        return writeShares;
    }

    @JsMethod
    public FileSharedWithState get(String filename) {
        return new FileSharedWithState(
                readShares.getOrDefault(filename, Collections.emptySet()),
                writeShares.getOrDefault(filename, Collections.emptySet()),
                links.getOrDefault(filename, Collections.emptySet())
        );
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

        Map<String, Set<LinkProperties>> newLinks = new HashMap<>();
        links.forEach((k, v) -> {
            if (k.equals(childName))
                newLinks.put(k, new HashSet<>(v));
        });

        return Optional.of(new SharedWithState(newReads, newWrites, newLinks));
    }

    public SharedWithState addLink(String filename, LinkProperties link) {
        Map<String, Set<LinkProperties>> newLinks = new HashMap<>();
        links.forEach((k, v) -> {
            newLinks.put(k, new HashSet<>(v));
        });

        newLinks.putIfAbsent(filename, new HashSet<>());
        newLinks.get(filename).removeIf(p -> p.label == link.label); // make sure we replace any old version
        newLinks.get(filename).add(link);

        return new SharedWithState(readShares, writeShares, newLinks);
    }

    public SharedWithState addLinks(String filename, Set<LinkProperties> newFileLinks) {
        Map<String, Set<LinkProperties>> newLinks = new HashMap<>();
        links.forEach((k, v) -> {
            newLinks.put(k, new HashSet<>(v));
        });

        if (! newFileLinks.isEmpty()) {
            newLinks.putIfAbsent(filename, new HashSet<>());
            newLinks.get(filename).addAll(newFileLinks);
        }
        return new SharedWithState(readShares, writeShares, newLinks);
    }

    public SharedWithState removeLink(String filename, long label) {
        Map<String, Set<LinkProperties>> newLinks = new HashMap<>();
        links.forEach((k, v) -> {
            newLinks.put(k, new HashSet<>(v));
        });

        Set<LinkProperties> val = newLinks.get(filename);
        Set<LinkProperties> updated = val.stream().filter(lp -> lp.label != label).collect(Collectors.toSet());
        if (updated.isEmpty())
            newLinks.remove(filename);
        else
            newLinks.put(filename, updated);

        return new SharedWithState(readShares, writeShares, newLinks);
    }

    public SharedWithState add(SharedWithCache.Access access, String filename, Set<String> names) {
        if (names.isEmpty())
            return this;
        Map<String, Set<String>> newReads = new HashMap<>();
        for (Map.Entry<String, Set<String>> e : readShares.entrySet()) {
            newReads.put(e.getKey(), new HashSet<>(e.getValue()));
        }
        Map<String, Set<String>> newWrites = new HashMap<>();
        for (Map.Entry<String, Set<String>> e : writeShares.entrySet()) {
            newWrites.put(e.getKey(), new HashSet<>(e.getValue()));
        }

        if (access == SharedWithCache.Access.READ) {
            newReads.putIfAbsent(filename, new HashSet<>());
            newReads.get(filename).addAll(names);
        } else if (access == SharedWithCache.Access.WRITE) {
            newWrites.putIfAbsent(filename, new HashSet<>());
            newWrites.get(filename).addAll(names);
        }

        return new SharedWithState(newReads, newWrites, links);
    }

    public SharedWithState remove(SharedWithCache.Access access, String filename, Set<String> names) {
        Map<String, Set<String>> newReads = new HashMap<>();
        for (Map.Entry<String, Set<String>> e : readShares.entrySet()) {
            newReads.put(e.getKey(), new HashSet<>(e.getValue()));
        }
        Map<String, Set<String>> newWrites = new HashMap<>();
        for (Map.Entry<String, Set<String>> e : writeShares.entrySet()) {
            newWrites.put(e.getKey(), new HashSet<>(e.getValue()));
        }

        Set<String> val = access == SharedWithCache.Access.READ ? newReads.get(filename) : newWrites.get(filename);
        if (val != null) {
            val.removeAll(names);
            if (val.isEmpty()) {
                if (access == SharedWithCache.Access.READ) {
                    newReads.remove(filename);
                } else {
                    newWrites.remove(filename);
                }
            }
        }

        return new SharedWithState(newReads, newWrites, links);
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

        Map<String, Set<LinkProperties>> newLinks = new HashMap<>();
        links.forEach((k, v) -> {
            newLinks.put(k, new HashSet<>(v));
        });

        newLinks.remove(filename);

        return new SharedWithState(newReads, newWrites, newLinks);
    }

    @JsMethod
    public boolean isShared(String filename) {
        return readShares.containsKey(filename) || writeShares.containsKey(filename);
    }

    @JsMethod
    public boolean hasLink(String filename) {
        return links.containsKey(filename);
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

        SortedMap<String, Cborable> linksState = new TreeMap<>();
        links.forEach((k, v) -> {
            linksState.put(k, new CborObject.CborList(v.stream().map(LinkProperties::toCbor).collect(Collectors.toList())));
        });

        state.put("l", CborObject.CborMap.build(linksState));
        return CborObject.CborMap.build(state);
    }

    public static SharedWithState fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for SharedWithState!");
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        CborObject.CborMap r = m.get("r", c -> (CborObject.CborMap) c);
        Function<Cborable, String> getString = c -> ((CborObject.CborString) c).value;
        Map<String, Set<String>> readShares = r.toMap(
                getString,
                c -> new HashSet<>(((CborObject.CborList)c).map(getString)));

        CborObject.CborMap w = m.get("w", c -> (CborObject.CborMap) c);
        Map<String, Set<String>> writehares = w.toMap(
                getString,
                c -> new HashSet<>(((CborObject.CborList)c).map(getString)));
        if (! m.containsKey("l"))
            return new SharedWithState(readShares, writehares, Collections.emptyMap());

        CborObject.CborMap l = m.get("l", c -> (CborObject.CborMap) c);
        Map<String, Set<LinkProperties>> links = l.toMap(
                getString,
                c -> new HashSet<>(((CborObject.CborList)c).map(LinkProperties::fromCbor)));

        return new SharedWithState(readShares, writehares, links);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SharedWithState that = (SharedWithState) o;
        return Objects.equals(readShares, that.readShares) && Objects.equals(writeShares, that.writeShares) && Objects.equals(links, that.links);
    }

    @Override
    public int hashCode() {
        return Objects.hash(readShares, writeShares, links);
    }
}
