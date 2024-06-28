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
    private final Map<String, Set<LinkProperties>> readLinks;
    private final Map<String, Set<LinkProperties>> writeLinks;

    public SharedWithState(Map<String, Set<String>> readShares,
                           Map<String, Set<String>> writeShares,
                           Map<String, Set<LinkProperties>> readLinks,
                           Map<String, Set<LinkProperties>> writeLinks) {
        this.readShares = readShares;
        this.writeShares = writeShares;
        this.readLinks = readLinks;
        this.writeLinks = writeLinks;
    }

    public boolean isEmpty() {
        return readShares.isEmpty() && writeShares.isEmpty();
    }

    public static SharedWithState empty() {
        return new SharedWithState(new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>());
    }

    public Map<String, Set<String>> readShares() {
        return readShares;
    }

    public Map<String, Set<String>> writeShares() {
        return writeShares;
    }

    @JsMethod
    public FileSharedWithState get(String filename) {
        return new FileSharedWithState(
                readShares.getOrDefault(filename, Collections.emptySet()),
                writeShares.getOrDefault(filename, Collections.emptySet()),
                readLinks.getOrDefault(filename, Collections.emptySet()),
                writeLinks.getOrDefault(filename, Collections.emptySet())
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

        Map<String, Set<LinkProperties>> newReadLinks = new HashMap<>();
        readLinks.forEach((k, v) -> {
            if (k.equals(childName))
                newReadLinks.put(k, new HashSet<>(v));
        });

        Map<String, Set<LinkProperties>> newWriteLinks = new HashMap<>();
        writeLinks.forEach((k, v) -> {
            if (k.equals(childName))
                newWriteLinks.put(k, new HashSet<>(v));
        });

        return Optional.of(new SharedWithState(newReads, newWrites, newReadLinks, newWriteLinks));
    }

    public SharedWithState addLink(SharedWithCache.Access access, String filename, LinkProperties link) {
        Map<String, Set<LinkProperties>> newReadLinks = new HashMap<>();
        readLinks.forEach((k, v) -> {
            newReadLinks.put(k, new HashSet<>(v));
        });

        Map<String, Set<LinkProperties>> newWriteLinks = new HashMap<>();
        writeLinks.forEach((k, v) -> {
            newWriteLinks.put(k, new HashSet<>(v));
        });

        if (access == SharedWithCache.Access.READ) {
            newReadLinks.putIfAbsent(filename, new HashSet<>());
            newReadLinks.get(filename).add(link);
        } else if (access == SharedWithCache.Access.WRITE) {
            newWriteLinks.putIfAbsent(filename, new HashSet<>());
            newWriteLinks.get(filename).add(link);
        }
        return new SharedWithState(readShares, writeShares, newReadLinks, newWriteLinks);
    }

    public SharedWithState addLinks(String filename, Set<LinkProperties> newFileReadLinks, Set<LinkProperties> newFileWritelinks) {
        Map<String, Set<LinkProperties>> newReadLinks = new HashMap<>();
        readLinks.forEach((k, v) -> {
            newReadLinks.put(k, new HashSet<>(v));
        });

        Map<String, Set<LinkProperties>> newWriteLinks = new HashMap<>();
        writeLinks.forEach((k, v) -> {
            newWriteLinks.put(k, new HashSet<>(v));
        });

        if (! newFileReadLinks.isEmpty()) {
            newReadLinks.putIfAbsent(filename, new HashSet<>());
            newReadLinks.get(filename).addAll(newFileReadLinks);
        }
        if (! newFileWritelinks.isEmpty()) {
            newWriteLinks.putIfAbsent(filename, new HashSet<>());
            newWriteLinks.get(filename).addAll(newFileWritelinks);
        }
        return new SharedWithState(readShares, writeShares, newReadLinks, newWriteLinks);
    }

    public SharedWithState removeLink(SharedWithCache.Access access, String filename, long label) {
        Map<String, Set<LinkProperties>> newReadLinks = new HashMap<>();
        readLinks.forEach((k, v) -> {
            newReadLinks.put(k, new HashSet<>(v));
        });

        Map<String, Set<LinkProperties>> newWriteLinks = new HashMap<>();
        writeLinks.forEach((k, v) -> {
            newWriteLinks.put(k, new HashSet<>(v));
        });

        if (access == SharedWithCache.Access.READ) {
            Set<LinkProperties> val = newReadLinks.get(filename);
            Set<LinkProperties> updated = val.stream().filter(lp -> lp.label != label).collect(Collectors.toSet());
            if (updated.isEmpty())
                newReadLinks.remove(filename);
            else
                newReadLinks.put(filename, updated);
        } else if (access == SharedWithCache.Access.WRITE) {
            Set<LinkProperties> val = newWriteLinks.get(filename);
            Set<LinkProperties> updated = val.stream().filter(lp -> lp.label != label).collect(Collectors.toSet());
            if (updated.isEmpty())
                newWriteLinks.remove(filename);
            else
                newWriteLinks.put(filename, updated);
        }
        return new SharedWithState(readShares, writeShares, newReadLinks, newWriteLinks);
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

        return new SharedWithState(newReads, newWrites, readLinks, writeLinks);
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

        return new SharedWithState(newReads, newWrites, readLinks, writeLinks);
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

        Map<String, Set<LinkProperties>> newReadLinks = new HashMap<>();
        readLinks.forEach((k, v) -> {
            newReadLinks.put(k, new HashSet<>(v));
        });

        Map<String, Set<LinkProperties>> newWriteLinks = new HashMap<>();
        writeLinks.forEach((k, v) -> {
            newWriteLinks.put(k, new HashSet<>(v));
        });

        newReadLinks.remove(filename);
        newWriteLinks.remove(filename);

        return new SharedWithState(newReads, newWrites, newReadLinks, newWriteLinks);
    }

    @JsMethod
    public boolean isShared(String filename) {
        return readShares.containsKey(filename) || writeShares.containsKey(filename);
    }

    @JsMethod
    public boolean hasLink(String filename) {
        return readLinks.containsKey(filename) || writeLinks.containsKey(filename);
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

        SortedMap<String, Cborable> readLinkState = new TreeMap<>();
        readLinks.forEach((k, v) -> {
            readLinkState.put(k, new CborObject.CborList(v.stream().map(LinkProperties::toCbor).collect(Collectors.toList())));
        });

        SortedMap<String, Cborable> writeLinkState = new TreeMap<>();
        writeLinks.forEach((k, v) -> {
            writeLinkState.put(k, new CborObject.CborList(v.stream().map(LinkProperties::toCbor).collect(Collectors.toList())));
        });

        state.put("rl", CborObject.CborMap.build(readLinkState));
        state.put("wl", CborObject.CborMap.build(writeLinkState));
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

        CborObject.CborMap rl = m.get("rl", c -> (CborObject.CborMap) c);
        Map<String, Set<LinkProperties>> readLinks = rl.toMap(
                getString,
                c -> new HashSet<>(((CborObject.CborList)c).map(LinkProperties::fromCbor)));

        CborObject.CborMap wl = m.get("wl", c -> (CborObject.CborMap) c);
        Map<String, Set<LinkProperties>> writeLinks = wl.toMap(
                getString,
                c -> new HashSet<>(((CborObject.CborList)c).map(LinkProperties::fromCbor)));

        return new SharedWithState(readShares, writehares, readLinks, writeLinks);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SharedWithState that = (SharedWithState) o;
        return Objects.equals(readShares, that.readShares) && Objects.equals(writeShares, that.writeShares) && Objects.equals(readLinks, that.readLinks) && Objects.equals(writeLinks, that.writeLinks);
    }

    @Override
    public int hashCode() {
        return Objects.hash(readShares, writeShares, readLinks, writeLinks);
    }
}
