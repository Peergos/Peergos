package peergos.shared.user;

import jsinterop.annotations.*;
import peergos.shared.cbor.*;

import java.util.*;
import java.util.function.*;
import java.util.stream.*;

/** Holds the sharing state for all the children of a directory
 *
 */
public class SharedWithState implements Cborable {
    private final Map<String, Set<String>> readShares;
    private final Map<String, Set<String>> writeShares;

    public SharedWithState(Map<String, Set<String>> readShares, Map<String, Set<String>> writeShares) {
        this.readShares = readShares;
        this.writeShares = writeShares;
    }

    public boolean isEmpty() {
        return readShares.isEmpty() && writeShares.isEmpty();
    }

    public static SharedWithState empty() {
        return new SharedWithState(new HashMap<>(), new HashMap<>());
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

        return new SharedWithState(newReads, newWrites);
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
        Map<String, Set<String>> readShares = r.toMap(
                getString,
                c -> new HashSet<>(((CborObject.CborList)c).map(getString)));

        CborObject.CborMap w = m.get("w", c -> (CborObject.CborMap) c);
        Map<String, Set<String>> writehares = w.toMap(
                getString,
                c -> new HashSet<>(((CborObject.CborList)c).map(getString)));

        return new SharedWithState(readShares, writehares);
    }
}
