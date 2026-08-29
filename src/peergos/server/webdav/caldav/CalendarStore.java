package peergos.server.webdav.caldav;

import peergos.shared.io.ipfs.api.JSONParser;
import peergos.shared.user.App;
import peergos.shared.user.UserContext;
import peergos.shared.user.fs.FileWrapper;
import peergos.shared.util.ArrayOps;
import peergos.shared.util.PathUtil;
import peergos.shared.util.Serialize;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * The calendar data the web calendar app reads and writes, presented as flat collections.
 *
 * On disk a calendar is sharded by the event's own UTC month, with recurring events pulled
 * out into their own directory:
 * <pre>
 *   &lt;user&gt;/.apps/calendar/data/App.config          {"calendars":[{name,directory,color}]}
 *   &lt;user&gt;/.apps/calendar/data/&lt;dir&gt;/calendar.inf  {"name","color"}
 *   &lt;user&gt;/.apps/calendar/data/&lt;dir&gt;/&lt;year&gt;/&lt;month&gt;/&lt;uid&gt;.ics
 *   &lt;user&gt;/.apps/calendar/data/&lt;dir&gt;/recurring/&lt;uid&gt;.ics
 * </pre>
 * CalDAV wants one flat collection per calendar, so this class hides the sharding: it
 * lists every .ics under a calendar directory as a direct member, and resolves a member
 * name back to the file it came from.
 *
 * These directories are all created as system folders, so they are hidden; unlike the file
 * bridge this class deliberately does not filter hidden files.
 */
public class CalendarStore {

    public static final String APP_NAME = "calendar";
    public static final String CONFIG_FILENAME = "App.config";
    public static final String CALENDAR_INFO_FILENAME = "calendar.inf";
    public static final String RECURRING_DIR = "recurring";
    public static final String ICS_SUFFIX = ".ics";
    public static final String DEFAULT_DIRECTORY = "default";
    private static final String DEFAULT_NAME = "My Calendar";
    private static final String DEFAULT_COLOUR = "#00a9ff";

    /** One calendar collection, as named by App.config and calendar.inf. */
    public static final class CalendarInfo {
        public final String directory;
        public final String name;
        public final String colour;
        /**
         * True when App.config lists this calendar, so the web app has it in the sidebar.
         * The bridge never writes App.config, so it must not delete one of these: the entry
         * would outlive the directory and the web app would show a calendar with nothing in
         * it and no way to remove it.
         */
        public final boolean configured;

        public CalendarInfo(String directory, String name, String colour, boolean configured) {
            this.directory = directory;
            this.name = name;
            this.colour = colour;
            this.configured = configured;
        }
    }

    /** One .ics file, addressed by the member name a client sees and the shard it lives in. */
    public static final class ObjectRef {
        /** The file name, which is the last segment of the resource's href. */
        public final String name;
        /** Path of the containing shard relative to the calendar directory, e.g. "2024/3". */
        public final String shard;
        public final FileWrapper file;
        /** Filled in on first read. Each ref belongs to a single request, so this is not shared. */
        private byte[] content;

        public ObjectRef(String name, String shard, FileWrapper file) {
            this.name = name;
            this.shard = shard;
            this.file = file;
        }

        public String etag() {
            return CalendarStore.etag(file);
        }
    }

    private final UserContext context;
    private final String username;
    private final Path dataDir;
    private volatile App app;
    /** How many past states a collection remembers before a client has to resync in full. */
    private static final int MAX_REMEMBERED_STATES = 32;
    private final Map<String, Listing> listings = new HashMap<>();
    private final Map<String, String> writerVersions = new HashMap<>();
    private final Map<String, Map<String, Map<String, String>>> history = new HashMap<>();

    public CalendarStore(UserContext context) {
        this.context = context;
        this.username = context.username;
        this.dataDir = App.getDataDir(APP_NAME, username);
    }

    public String username() {
        return username;
    }

    public Path calendarPath(String directory) {
        return dataDir.resolve(directory);
    }

    /**
     * The calendars this account owns. Entries in App.config are authoritative for the
     * name and colour; a directory that exists without an entry is still served, so a
     * calendar created outside the web app is never invisible.
     */
    public List<CalendarInfo> listCalendars() {
        Map<String, CalendarInfo> byDirectory = new LinkedHashMap<>();
        for (Map<String, Object> entry : configuredCalendars()) {
            Object directory = entry.get("directory");
            Object owner = entry.get("owner");
            if (! (directory instanceof String))
                continue;
            // A calendar shared with us lives under its owner's account, not ours.
            if (owner instanceof String && ! owner.equals(username))
                continue;
            byDirectory.put((String) directory, new CalendarInfo((String) directory,
                    entry.get("name") instanceof String ? (String) entry.get("name") : (String) directory,
                    entry.get("color") instanceof String ? (String) entry.get("color") : DEFAULT_COLOUR, true));
        }
        // With no App.config the web app falls back to this same entry, so it is as much
        // part of its list as a written one.
        if (byDirectory.isEmpty())
            byDirectory.put(DEFAULT_DIRECTORY,
                    new CalendarInfo(DEFAULT_DIRECTORY, DEFAULT_NAME, DEFAULT_COLOUR, true));
        for (FileWrapper child : children(dataDir)) {
            if (! child.isDirectory() || byDirectory.containsKey(child.getName()))
                continue;
            byDirectory.put(child.getName(), readCalendarInfo(child.getName()));
        }
        return new ArrayList<>(byDirectory.values());
    }

    public Optional<CalendarInfo> getCalendar(String directory) {
        return listCalendars().stream().filter(c -> c.directory.equals(directory)).findFirst();
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
     * which is what every change used to cost, and writing a log into the user's app data
     * would mean a second writer on files the web calendar app owns.
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
        Optional<FileWrapper> calendar = getByPath(calendarPath(directory));
        if (calendar.isEmpty())
            return Optional.empty();
        return context.network.mutable.getPointer(calendar.get().owner(), calendar.get().writer()).join()
                .map(ArrayOps::bytesToHex);
    }

    /** Every .ics under a calendar, from both the month shards and recurring/. */
    public List<ObjectRef> listObjects(String directory) {
        return listing(directory).objects;
    }

    private List<ObjectRef> readObjects(String directory) {
        List<ObjectRef> objects = new ArrayList<>();
        for (FileWrapper shard : children(calendarPath(directory))) {
            if (! shard.isDirectory())
                continue;
            if (shard.getName().equals(RECURRING_DIR)) {
                collectIcs(shard, RECURRING_DIR, objects);
            } else if (isYear(shard.getName())) {
                for (FileWrapper month : children(shard)) {
                    if (month.isDirectory())
                        collectIcs(month, shard.getName() + "/" + month.getName(), objects);
                }
            }
        }
        return objects;
    }

    public Optional<ObjectRef> getObject(String directory, String name) {
        return listObjects(directory).stream().filter(o -> o.name.equals(name)).findFirst();
    }

    /**
     * A calendar-query reads each object to filter it and then again to return its
     * calendar-data, so the content is remembered on the ref after the first read.
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
     * The shard an object belongs in, matching what the web calendar app writes: recurring
     * events live in their own directory, everything else under the UTC year and month of
     * its start, named 1-based and unpadded.
     *
     * Empty when the object has no readable start and does not recur, which means there is
     * nowhere to put it that the web app would ever look.
     */
    public static Optional<String> shardFor(ICal.Summary summary) {
        if (summary.recurring)
            return Optional.of(RECURRING_DIR);
        return ICal.shard(summary).map(month -> month.getYear() + "/" + month.getMonthValue());
    }

    /**
     * Writes one calendar object into the shard its content calls for, creating the shard
     * and the calendar directory if this is the first event in them.
     *
     * When the event's date has moved the old file is removed first: a crash between the
     * two leaves the client's PUT unacknowledged so it retries, whereas writing first would
     * leave the same member name in two shards, which is silent and permanent.
     */
    public void putObject(String directory, String name, byte[] content, Optional<ObjectRef> existing) {
        String shard = shardFor(ICal.summarise(new String(content, StandardCharsets.UTF_8)))
                .orElseThrow(() -> new IllegalArgumentException("No start date to file this event under"));
        existing.filter(o -> ! o.shard.equals(shard)).ifPresent(old -> deleteObject(directory, old));
        write(directory + "/" + shard + "/" + name, content);
    }

    public void deleteObject(String directory, ObjectRef object) {
        remove(calendarPath(directory).resolve(object.shard).resolve(object.name));
    }

    /**
     * Creates the directory and its calendar.inf. App.config is deliberately not touched:
     * the web app holds it in memory for the life of a tab and writes the whole thing back,
     * so a second writer here would have its entry dropped by the next edit in an open tab.
     * The cost is that the calendar is invisible in the web app until that app also lists
     * the directories it finds on disk.
     */
    public void createCalendar(String directory, String name, String colour) {
        write(directory + "/" + CALENDAR_INFO_FILENAME,
                ("{\"name\":" + quote(name) + ",\"color\":" + quote(colour) + "}").getBytes(StandardCharsets.UTF_8));
    }

    public void deleteCalendar(String directory) {
        remove(calendarPath(directory));
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

    /** Writes through App, so the layout is exactly what the web calendar app produces. */
    private void write(String relativePath, byte[] content) {
        app().writeInternal(PathUtil.get(relativePath), content, null).join();
        forget(relativePath.substring(0, Math.max(0, relativePath.indexOf('/'))));
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
     * data directories, which an account that has never opened the calendar should not
     * acquire just by running the bridge.
     */
    private synchronized App app() {
        if (app == null)
            app = App.init(context, APP_NAME).join();
        return app;
    }

    /**
     * A strong ETag from the file's content hash, falling back to the weak length+mtime
     * form when a file predates tree hashes. Calendar clients lean on ETags for conflict
     * detection, and length+mtime collides easily for events edited twice in one second.
     */
    public static String etag(FileWrapper file) {
        return file.getFileProperties().treeHash
                .map(h -> "\"" + h.toString() + "\"")
                .orElseGet(() -> "W/\"" + file.getFileProperties().size + "-"
                        + file.getFileProperties().modified.toEpochSecond(java.time.ZoneOffset.UTC) + "\"");
    }

    private void collectIcs(FileWrapper shard, String shardPath, List<ObjectRef> into) {
        for (FileWrapper file : children(shard)) {
            if (! file.isDirectory() && file.getName().endsWith(ICS_SUFFIX))
                into.add(new ObjectRef(file.getName(), shardPath, file));
        }
    }

    private static boolean isYear(String name) {
        return name.length() == 4 && name.chars().allMatch(Character::isDigit);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> configuredCalendars() {
        Optional<byte[]> config = readFile(dataDir.resolve(CONFIG_FILENAME));
        if (config.isEmpty())
            return Collections.emptyList();
        try {
            Object parsed = JSONParser.parse(new String(config.get(), StandardCharsets.UTF_8));
            Object calendars = ((Map<String, Object>) parsed).get("calendars");
            if (! (calendars instanceof List))
                return Collections.emptyList();
            return ((List<Object>) calendars).stream()
                    .filter(c -> c instanceof Map)
                    .map(c -> (Map<String, Object>) c)
                    .collect(Collectors.toList());
        } catch (RuntimeException e) {
            return Collections.emptyList();
        }
    }

    @SuppressWarnings("unchecked")
    private CalendarInfo readCalendarInfo(String directory) {
        Optional<byte[]> info = readFile(calendarPath(directory).resolve(CALENDAR_INFO_FILENAME));
        if (info.isPresent()) {
            try {
                Map<String, Object> json = (Map<String, Object>) JSONParser
                        .parse(new String(info.get(), StandardCharsets.UTF_8));
                return new CalendarInfo(directory,
                        json.get("name") instanceof String ? (String) json.get("name") : directory,
                        json.get("color") instanceof String ? (String) json.get("color") : DEFAULT_COLOUR,
                        false);
            } catch (RuntimeException e) {
                // fall through to the directory name
            }
        }
        return new CalendarInfo(directory, directory, DEFAULT_COLOUR, false);
    }

    private Optional<byte[]> readFile(Path path) {
        return getByPath(path).map(f ->
                Serialize.readFully(f.getInputStream(context.network, context.crypto, f.getSize(), l -> {}).join(),
                        f.getSize()).join());
    }

    private List<FileWrapper> children(Path path) {
        Optional<FileWrapper> dir = getByPath(path);
        if (dir.isEmpty() || ! dir.get().isDirectory())
            return Collections.emptyList();
        return children(dir.get());
    }

    private List<FileWrapper> children(FileWrapper directory) {
        return new ArrayList<>(directory.getChildren(context.crypto.hasher, context.network).join());
    }

    private Optional<FileWrapper> getByPath(Path path) {
        return context.getByPath(path.toString().replace('\\', '/')).join();
    }
}
