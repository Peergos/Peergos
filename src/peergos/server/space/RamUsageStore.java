package peergos.server.space;

import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.util.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

public class RamUsageStore implements UsageStore {

    private final State state;
    private final Path statePath;
    private boolean initializedFully = false;

    public RamUsageStore(State state, Path statePath) {
        this.state = state;
        this.statePath = statePath;
    }

    @Override
    public void addUserIfAbsent(String username) {
        state.usage.putIfAbsent(username, new UserUsage(0));
    }

    @Override
    public UserUsage getUsage(String owner) {
        return state.usage.get(owner);
    }

    @Override
    public void addWriter(String owner, PublicKeyHash writer) {
        state.currentView.computeIfAbsent(writer, k -> new WriterUsage(owner, MaybeMultihash.empty(), 0, Collections.emptySet()));
    }

    @Override
    public Set<PublicKeyHash> getAllWriters() {
        return state.currentView.keySet();
    }

    @Override
    public Set<PublicKeyHash> getAllWriters(PublicKeyHash owner) {
        Set<PublicKeyHash> res = new HashSet<>();
        getAllWriters(owner, res);
        return res;
    }

    private void getAllWriters(PublicKeyHash writer, Set<PublicKeyHash> res) {
        res.add(writer);
        WriterUsage current = state.currentView.get(writer);
        for (PublicKeyHash ownedKey : current.ownedKeys()) {
            if (! res.contains(ownedKey))
                getAllWriters(ownedKey, res);
        }
    }

    @Override
    public void confirmUsage(String owner, PublicKeyHash writer, long usageDelta, boolean errored) {
        UserUsage usage = state.usage.get(owner);
        usage.confirmUsage(writer, usageDelta);
        usage.clearPending(writer);
        usage.setErrored(errored);
    }

    @Override
    public WriterUsage getUsage(PublicKeyHash writer) {
        return state.currentView.get(writer);
    }

    @Override
    public void updateWriterUsage(PublicKeyHash writer,
                                  MaybeMultihash target,
                                  Set<PublicKeyHash> removedOwnedKeys,
                                  Set<PublicKeyHash> addedOwnedKeys,
                                  long retainedStorage) {
        state.currentView.get(writer).update(target, removedOwnedKeys, addedOwnedKeys, retainedStorage);
    }

    @Override
    public void addPendingUsage(String username, PublicKeyHash writer, int size) {
        state.usage.get(username).addPending(writer, size);
    }

    @Override
    public void initialized() {
        this.initializedFully = true;
    }

    /**
     * Write current view of usages to this.statePath, completing any pending operations
     */
    @Override
    public synchronized void close() {
        try {
            if (initializedFully) {
                store();
                Logging.LOG().info("Successfully stored usage-state to " + this.statePath);
            }
        } catch (Throwable t) {
            Logging.LOG().info("Failed to  store "+ this);
            t.printStackTrace();
        }
    }

    @Override
    public List<Pair<Multihash, String>> getAllTargets() {
        return state.currentView.values().stream()
                .flatMap(wu -> wu.target()
                        .toOptional()
                        .map(h -> new Pair<>(h, wu.owner))
                        .stream())
                .collect(Collectors.toList());
    }

    /**
     * Read local file with cached user usages.
     * @return previous usages
     * @throws IOException
     */
    private static State load(Path statePath) throws IOException {
        Logging.LOG().info("Reading state from "+ statePath +" which exists ? "+ Files.exists(statePath) +" from cwd "+ System.getProperty("cwd"));
        byte[] data = Files.readAllBytes(statePath);
        CborObject object = CborObject.deserialize(new CborDecoder(new ByteArrayInputStream(data)), data.length);
        return State.fromCbor(object);
    }

    /**
     * Store usages
     * @throws IOException
     */
    private synchronized void store() throws IOException {
        byte[] serialized = state.toCbor().serialize();
        Logging.LOG().info("Writing "+ serialized.length +" bytes to "+ statePath);
        Files.write(
                statePath,
                serialized,
                StandardOpenOption.CREATE);
    }

    public static RamUsageStore build(Path statePath) {
        State state;
        try {
            // Read stored usages and update current view.
            state = load(statePath);
            Logging.LOG().info("Successfully loaded usage-state from "+ statePath);
        } catch (IOException ioe) {
            Logging.LOG().info("Could not read usage-state from "+ statePath);
            // calculate usage from scratch
            state = new State(new ConcurrentHashMap<>(), new ConcurrentHashMap<>());
        }
        return new RamUsageStore(state, statePath);
    }

    public static class State implements Cborable {
        final Map<PublicKeyHash, WriterUsage> currentView;
        final Map<String, UserUsage> usage;

        public State(Map<PublicKeyHash, WriterUsage> currentView, Map<String, UserUsage> usage) {
            this.currentView = currentView;
            this.usage = usage;
        }

        @Override
        public CborObject toCbor() {
            TreeMap<CborObject, ? extends Cborable> viewsMap = currentView.entrySet()
                    .stream()
                    .collect(Collectors.toMap(
                            e -> e.getKey().toCbor(),
                            e -> (Cborable) (e.getValue()),
                            (a,b) -> a,
                            TreeMap::new
                    ));

            CborObject.CborList views = new CborObject.CborList(viewsMap);
            CborObject.CborMap usages = CborObject.CborMap.build(new HashMap<>(usage));
            Map<String, Cborable> map = new HashMap<>();
            map.put("views", views);
            map.put("usages", usages);
            return CborObject.CborMap.build(map);
        }

        public static State fromCbor(CborObject cbor) {
            CborObject.CborMap map = (CborObject.CborMap) cbor;
            CborObject.CborList viewsMap = (CborObject.CborList) map.get("views");
            CborObject.CborMap usagesMap = (CborObject.CborMap) map.get("usages");

            return new State(viewsMap.getMap(PublicKeyHash::fromCbor, WriterUsage::fromCbor),
                    usagesMap.toMap(e -> ((CborObject.CborString) e).value, UserUsage::fromCbor));
        }

        public Map<String, UserUsage> getUsage() {
            return new ConcurrentHashMap<>(usage);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            State state = (State) o;

            if (currentView != null ? !currentView.equals(state.currentView) : state.currentView != null) return false;
            return usage != null ? usage.equals(state.usage) : state.usage == null;
        }

        @Override
        public int hashCode() {
            int result = currentView != null ? currentView.hashCode() : 0;
            result = 31 * result + (usage != null ? usage.hashCode() : 0);
            return result;
        }
    }
}
