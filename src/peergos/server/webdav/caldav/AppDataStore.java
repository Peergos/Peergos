package peergos.server.webdav.caldav;

import peergos.shared.io.ipfs.api.JSONParser;
import peergos.shared.user.App;
import peergos.shared.user.UserContext;
import peergos.shared.user.fs.FileWrapper;
import peergos.shared.util.ArrayOps;
import peergos.shared.util.PathUtil;
import peergos.shared.util.Serialize;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Collections of DAV resources held in a Peergos app's data directory.
 *
 * Calendars and address books differ only in where a new object is filed and how the
 * existing ones are found; everything else — how collections are named, ETags, the sync
 * token, the change log, reads and writes — is the same, and lives here.
 *
 * <pre>
 *   &lt;user&gt;/.apps/&lt;app&gt;/data/App.config      {"&lt;configKey&gt;":[{name,directory,color}]}
 *   &lt;user&gt;/.apps/&lt;app&gt;/data/&lt;dir&gt;/&lt;info&gt;    {"name","color"}
 *   &lt;user&gt;/.apps/&lt;app&gt;/data/&lt;dir&gt;/...       the objects, laid out by the subclass
 * </pre>
 *
 * These directories are created as system folders, so they are hidden; unlike the file
 * bridge this class deliberately does not filter hidden files.
 */
public abstract class AppDataStore {

    public static final String CONFIG_FILENAME = "App.config";
    /** How many past states a collection remembers before a client has to resync in full. */
    private static final int MAX_REMEMBERED_STATES = 32;

    /** One collection, as named by App.config and its own info file. */
    public static final class CollectionInfo {
        public final String directory;
        public final String name;
        /** Empty where the collection kind has no colour. */
        public final String colour;
        /**
         * True when App.config lists this collection, so the web app has it in its sidebar.
         * The bridge never writes App.config, so it must not delete one of these: the entry
         * would outlive the directory and the web app would show a collection with nothing
         * in it and no way to remove it.
         */
        public final boolean configured;

        public CollectionInfo(String directory, String name, String colour, boolean configured) {
            this.directory = directory;
            this.name = name;
            this.colour = colour;
            this.configured = configured;
        }
    }

    /** One stored object, addressed by the member name a client sees. */
    public static final class ObjectRef {
        /** The file name, which is the last segment of the resource's href. */
        public final String name;
        /** Where it sits relative to the collection directory; empty for a flat collection. */
        public final String shard;
        public final FileWrapper file;
        /** Filled in on first read. Each ref belongs to one listing, so this is not shared. */
        private byte[] content;

        public ObjectRef(String name, String shard, FileWrapper file) {
            this.name = name;
            this.shard = shard;
            this.file = file;
        }

        public String etag() {
            return AppDataStore.etag(file);
        }
    }

    /**
     * A collection's members together with the token naming that exact state.
     *
     * The token is derived from the members themselves, not from the writer version, so it
     * only moves when the collection really changes — and it doubles as the key the change
     * log is indexed by.
     */
    public static final class Listing {
        public final String token;
        public final List<ObjectRef> objects;
        /** name to ETag, kept so a later listing can be diffed against this one. */
        final Map<String, String> etags;

        Listing(String token, List<ObjectRef> objects, Map<String, String> etags) {
            this.token = token;
            this.objects = objects;
            this.etags = etags;
        }
    }

    /** What a client holding an older token has yet to see. */
    public static final class Changes {
        public final List<ObjectRef> changed;
        public final List<String> removed;
        public final String token;

        Changes(List<ObjectRef> changed, List<String> removed, String token) {
            this.changed = changed;
            this.removed = removed;
            this.token = token;
        }
    }

    protected final UserContext context;
    private final String username;
    private final Path dataDir;
    private final String appName;
    private final String configKey;
    private final String infoFilename;
    private final String suffix;
    private final String defaultDirectory;
    private final String defaultName;
    private final String defaultColour;
    private volatile App app;

    private final Map<String, Listing> listings = new HashMap<>();
    private final Map<String, String> writerVersions = new HashMap<>();
    private final Map<String, Map<String, Map<String, String>>> history = new HashMap<>();

    protected AppDataStore(UserContext context, String appName, String configKey, String infoFilename,
                           String suffix, String defaultDirectory, String defaultName, String defaultColour) {
        this.context = context;
        this.username = context.username;
        this.appName = appName;
        this.configKey = configKey;
        this.infoFilename = infoFilename;
        this.suffix = suffix;
        this.defaultDirectory = defaultDirectory;
        this.defaultName = defaultName;
        this.defaultColour = defaultColour;
        this.dataDir = App.getDataDir(appName, username);
    }

    /** Every object in a collection, however the subclass lays them out on disk. */
    protected abstract List<ObjectRef> readObjects(String directory);

    /**
     * Where a new object belongs relative to the collection directory, from its content.
     * Empty means there is nowhere it could be stored that a reader would find it.
     */
    protected abstract Optional<String> shardFor(byte[] content);

    public String username() {
        return username;
    }

    public String suffix() {
        return suffix;
    }

    public Path collectionPath(String directory) {
        return dataDir.resolve(directory);
    }

    /**
     * The collections this account owns. Entries in App.config are authoritative for the
     * name and colour; a directory that exists without an entry is still served, so a
     * collection created outside the web app is never invisible.
     */
    public List<CollectionInfo> listCollections() {
        Map<String, CollectionInfo> byDirectory = new LinkedHashMap<>();
        for (Map<String, Object> entry : configuredCollections()) {
            Object directory = entry.get("directory");
            Object owner = entry.get("owner");
            if (! (directory instanceof String))
                continue;
            // A collection shared with us lives under its owner's account, not ours.
            if (owner instanceof String && ! owner.equals(username))
                continue;
            byDirectory.put((String) directory, new CollectionInfo((String) directory,
                    entry.get("name") instanceof String ? (String) entry.get("name") : (String) directory,
                    entry.get("color") instanceof String ? (String) entry.get("color") : defaultColour, true));
        }
        // With no App.config the web app falls back to this same entry, so it is as much
        // part of its list as a written one.
        if (byDirectory.isEmpty())
            byDirectory.put(defaultDirectory,
                    new CollectionInfo(defaultDirectory, defaultName, defaultColour, true));
        for (FileWrapper child : children(dataDir)) {
            if (! child.isDirectory() || byDirectory.containsKey(child.getName()))
                continue;
            byDirectory.put(child.getName(), readCollectionInfo(child.getName()));
        }
        return new ArrayList<>(byDirectory.values());
    }

    public Optional<CollectionInfo> getCollection(String directory) {
        return listCollections().stream().filter(c -> c.directory.equals(directory)).findFirst();
    }

    /**
     * The collection as it is now, reusing the last listing while the writer has not moved.
     *
     * The writer version is too coarse to be a token — it moves on any write in the account
     * — but it is exactly right as a cheap "could anything have changed?" guard, and it is
     * one pointer read against a full directory walk.
     */
    public synchronized Listing listing(String directory) {
        Optional<String> version = writerVersion(directory);
        Listing cached = listings.get(directory);
        if (cached != null && version.isPresent() && version.get().equals(writerVersions.get(directory)))
            return cached;

        List<ObjectRef> objects = readObjects(directory);
        Map<String, String> etags = new TreeMap<>();
        for (ObjectRef object : objects)
            etags.put(object.name, object.etag());
        Listing listing = new Listing(tokenFor(etags), objects, etags);

        listings.put(directory, listing);
        version.ifPresent(v -> writerVersions.put(directory, v));
        Map<String, Map<String, String>> log = history.computeIfAbsent(directory,
                d -> new LinkedHashMap<>() {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, Map<String, String>> eldest) {
                        return size() > MAX_REMEMBERED_STATES;
                    }
                });
        log.remove(listing.token);
        log.put(listing.token, etags);
        return listing;
    }

    /**
     * What changed since the given token, or empty if that state is no longer remembered —
     * which the caller must answer with DAV:valid-sync-token so the client resyncs in full.
     *
     * The history is in memory only. A restart therefore forces one resync per collection,
     * and writing a log into the user's app data would mean a second writer on files the
     * web app owns.
     */
    public synchronized Optional<Changes> changesSince(String directory, String token) {
        Listing now = listing(directory);
        if (now.token.equals(token))
            return Optional.of(new Changes(Collections.emptyList(), Collections.emptyList(), now.token));
        Map<String, String> before = history.getOrDefault(directory, Collections.emptyMap()).get(token);
        if (before == null)
            return Optional.empty();
        List<ObjectRef> changed = now.objects.stream()
                .filter(o -> ! o.etag().equals(before.get(o.name)))
                .collect(Collectors.toList());
        List<String> removed = before.keySet().stream()
                .filter(name -> ! now.etags.containsKey(name))
                .collect(Collectors.toList());
        return Optional.of(new Changes(changed, removed, now.token));
    }

    /** The current token, used for both DAV:sync-token and the calendarserver getctag. */
    public String token(String directory) {
        return listing(directory).token;
    }

    public List<ObjectRef> listObjects(String directory) {
        return listing(directory).objects;
    }

    public Optional<ObjectRef> getObject(String directory, String name) {
        return listObjects(directory).stream().filter(o -> o.name.equals(name)).findFirst();
    }

    /**
     * A query reads each object to filter it and then again to return its data, so the
     * content is remembered on the ref after the first read.
     */
    public byte[] read(ObjectRef object) {
        if (object.content == null) {
            long size = object.file.getSize();
            object.content = Serialize.readFully(object.file
                    .getInputStream(context.network, context.crypto, size, l -> {}).join(), size).join();
        }
        return object.content;
    }

    // ------------------------------------------------------------------ writing

    /**
     * Writes one object where its content says it belongs, creating the collection
     * directory if this is the first object in it.
     *
     * When an object moves between shards the old file is removed first: a crash between
     * the two leaves the client's PUT unacknowledged so it retries, whereas writing first
     * would leave the same member name in two places, which is silent and permanent.
     */
    /** Whether this content has somewhere it could be stored that a reader would find it. */
    public boolean canStore(byte[] content) {
        return shardFor(content).isPresent();
    }

    public void putObject(String directory, String name, byte[] content, Optional<ObjectRef> existing) {
        String shard = shardFor(content)
                .orElseThrow(() -> new IllegalArgumentException("Nowhere to file this object"));
        existing.filter(o -> ! o.shard.equals(shard)).ifPresent(old -> deleteObject(directory, old));
        write(join(directory, shard, name), content);
    }

    public void deleteObject(String directory, ObjectRef object) {
        remove(collectionPath(directory).resolve(object.shard).resolve(object.name));
    }

    /**
     * Creates the directory and its info file. App.config is deliberately not touched: the
     * web app holds it in memory for the life of a tab and writes the whole thing back, so
     * a second writer here would have its entry dropped by the next edit in an open tab.
     * The cost is that the collection is invisible in the web app until that app also lists
     * the directories it finds on disk.
     */
    public void createCollection(String directory, String name, String colour) {
        StringBuilder json = new StringBuilder("{\"name\":").append(quote(name));
        if (! colour.isEmpty())
            json.append(",\"color\":").append(quote(colour));
        write(join(directory, "", infoFilename), json.append("}").toString().getBytes(StandardCharsets.UTF_8));
    }

    public void deleteCollection(String directory) {
        remove(collectionPath(directory));
    }

    private static String join(String... segments) {
        return Arrays.stream(segments).filter(s -> ! s.isEmpty()).collect(Collectors.joining("/"));
    }

    private static String quote(String value) {
        StringBuilder quoted = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            if (c == '"' || c == '\\')
                quoted.append('\\').append(c);
            else if (c < 0x20)
                quoted.append(String.format("\\u%04x", (int) c));
            else
                quoted.append(c);
        }
        return quoted.append('"').toString();
    }

    /** Writes through App, so the layout is exactly what a web app for it would produce. */
    private void write(String relativePath, byte[] content) {
        app().writeInternal(PathUtil.get(relativePath), content, null).join();
        int slash = relativePath.indexOf('/');
        forget(slash < 0 ? relativePath : relativePath.substring(0, slash));
    }

    /**
     * Drop the cached listing for a collection we have just written to. The writer version
     * would catch it anyway, but only once the pointer cache agrees, and our own writes are
     * the one case where we already know for certain.
     */
    private synchronized void forget(String directory) {
        listings.remove(directory);
        writerVersions.remove(directory);
    }

    private void remove(Path path) {
        Path relative = dataDir.relativize(path);
        if (relative.getNameCount() > 0)
            forget(relative.getName(0).toString());
        Optional<FileWrapper> file = getByPath(path);
        if (file.isEmpty())
            return;
        FileWrapper parent = getByPath(path.getParent())
                .orElseThrow(() -> new IllegalStateException("No parent of " + path));
        file.get().remove(parent, path, context).join();
    }

    /**
     * Created on first write rather than up front, because initialising it makes the app
     * data directories, which an account that has never opened the app should not acquire
     * just by running the bridge.
     */
    private synchronized App app() {
        if (app == null)
            app = App.init(context, appName).join();
        return app;
    }

    // ------------------------------------------------------------------ reading

    /**
     * A strong ETag from the file's content hash, falling back to the weak length+mtime
     * form when a file predates tree hashes. Clients lean on ETags for conflict detection,
     * and length+mtime collides easily for an object edited twice in one second.
     */
    public static String etag(FileWrapper file) {
        return file.getFileProperties().treeHash
                .map(h -> "\"" + h.toString() + "\"")
                .orElseGet(() -> "W/\"" + file.getFileProperties().size + "-"
                        + file.getFileProperties().modified.toEpochSecond(java.time.ZoneOffset.UTC) + "\"");
    }

    private static String tokenFor(Map<String, String> etags) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Map.Entry<String, String> entry : etags.entrySet()) {
                digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(entry.getValue().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            return ArrayOps.bytesToHex(digest.digest()).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Optional<String> writerVersion(String directory) {
        Optional<FileWrapper> collection = getByPath(collectionPath(directory));
        if (collection.isEmpty())
            return Optional.empty();
        return context.network.mutable.getPointer(collection.get().owner(), collection.get().writer()).join()
                .map(ArrayOps::bytesToHex);
    }

    /** Adds every object file directly inside a directory to the listing. */
    protected void collect(FileWrapper directory, String shard, List<ObjectRef> into) {
        for (FileWrapper file : children(directory)) {
            if (! file.isDirectory() && file.getName().endsWith(suffix))
                into.add(new ObjectRef(file.getName(), shard, file));
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> configuredCollections() {
        Optional<byte[]> config = readFile(dataDir.resolve(CONFIG_FILENAME));
        if (config.isEmpty())
            return Collections.emptyList();
        try {
            Object parsed = JSONParser.parse(new String(config.get(), StandardCharsets.UTF_8));
            Object collections = ((Map<String, Object>) parsed).get(configKey);
            if (! (collections instanceof List))
                return Collections.emptyList();
            return ((List<Object>) collections).stream()
                    .filter(c -> c instanceof Map)
                    .map(c -> (Map<String, Object>) c)
                    .collect(Collectors.toList());
        } catch (RuntimeException e) {
            return Collections.emptyList();
        }
    }

    @SuppressWarnings("unchecked")
    private CollectionInfo readCollectionInfo(String directory) {
        Optional<byte[]> info = readFile(collectionPath(directory).resolve(infoFilename));
        if (info.isPresent()) {
            try {
                Map<String, Object> json = (Map<String, Object>) JSONParser
                        .parse(new String(info.get(), StandardCharsets.UTF_8));
                return new CollectionInfo(directory,
                        json.get("name") instanceof String ? (String) json.get("name") : directory,
                        json.get("color") instanceof String ? (String) json.get("color") : defaultColour,
                        false);
            } catch (RuntimeException e) {
                // fall through to the directory name
            }
        }
        return new CollectionInfo(directory, directory, defaultColour, false);
    }

    private Optional<byte[]> readFile(Path path) {
        return getByPath(path).map(f ->
                Serialize.readFully(f.getInputStream(context.network, context.crypto, f.getSize(), l -> {}).join(),
                        f.getSize()).join());
    }

    protected List<FileWrapper> children(Path path) {
        Optional<FileWrapper> dir = getByPath(path);
        if (dir.isEmpty() || ! dir.get().isDirectory())
            return Collections.emptyList();
        return children(dir.get());
    }

    protected List<FileWrapper> children(FileWrapper directory) {
        return new ArrayList<>(directory.getChildren(context.crypto.hasher, context.network).join());
    }

    private Optional<FileWrapper> getByPath(Path path) {
        return context.getByPath(path.toString().replace('\\', '/')).join();
    }
}
