package peergos.shared.user.fs.archive;

import jsinterop.annotations.*;

import java.time.*;
import java.util.*;
import java.util.stream.*;

/** The listing of a zip archive: every entry, and the directory tree their paths imply.
 *
 *  Archives routinely omit directory entries, so the tree is derived from the entry paths and any
 *  explicit directory entry is merged into it.
 */
@JsType
public class ZipIndex {

    private final List<ZipEntry> entries;
    private final Map<String, ZipEntry> byPath;
    private final Map<String, List<ZipEntry>> children;
    /** Entries dropped because their name escaped the archive root. */
    public final int rejected;

    @JsIgnore
    public ZipIndex(List<ZipEntry> entries, Map<String, ZipEntry> byPath, Map<String, List<ZipEntry>> children, int rejected) {
        this.entries = entries;
        this.byPath = byPath;
        this.children = children;
        this.rejected = rejected;
    }

    @JsIgnore
    public List<ZipEntry> getEntries() {
        return entries;
    }

    public int size() {
        return entries.size();
    }

    /** The contents of a directory within the archive, "" being the archive root.
     */
    @JsIgnore
    public List<ZipEntry> listDirectory(String path) {
        String dir = ZipEntry.normalisePath(path).orElseThrow(() -> new IllegalStateException("Invalid path: " + path));
        if (! dir.isEmpty() && ! isDirectory(dir))
            throw new IllegalStateException("Not a directory in this archive: " + path);
        return children.getOrDefault(dir, Collections.emptyList());
    }

    @JsIgnore
    public Optional<ZipEntry> get(String path) {
        return ZipEntry.normalisePath(path).flatMap(p -> Optional.ofNullable(byPath.get(p)));
    }

    @JsIgnore
    public boolean isDirectory(String path) {
        return get(path).map(e -> e.isDirectory).orElse(false);
    }

    /** The total decompressed size of the archive's files, which is what an extraction would cost.
     */
    public double getTotalSize() {
        return entries.stream().mapToLong(e -> e.size).sum();
    }

    @JsIgnore
    public static ZipIndex build(List<ZipEntry> parsed, int rejected) {
        Map<String, ZipEntry> byPath = new LinkedHashMap<>();
        for (ZipEntry entry : parsed) {
            ZipEntry existing = byPath.get(entry.path);
            // a later duplicate wins, matching what unzip extracts, unless it would hide a directory
            if (existing == null || ! existing.isDirectory)
                byPath.put(entry.path, entry);
        }

        List<ZipEntry> ordered = new ArrayList<>(byPath.values());
        for (ZipEntry entry : ordered) {
            for (String parent = entry.getParentPath(); ! parent.isEmpty(); ) {
                ZipEntry existing = byPath.get(parent);
                if (existing != null && existing.isDirectory)
                    break;
                byPath.put(parent, ZipEntry.implicitDirectory(parent, entry.modified));
                int slash = parent.lastIndexOf('/');
                parent = slash < 0 ? "" : parent.substring(0, slash);
            }
        }

        Map<String, List<ZipEntry>> children = new HashMap<>();
        for (ZipEntry entry : byPath.values())
            children.computeIfAbsent(entry.getParentPath(), p -> new ArrayList<>()).add(entry);
        for (List<ZipEntry> siblings : children.values())
            siblings.sort((a, b) -> a.isDirectory != b.isDirectory ?
                    (a.isDirectory ? -1 : 1) :
                    a.getName().compareToIgnoreCase(b.getName()));

        List<ZipEntry> all = byPath.values().stream()
                .sorted(Comparator.comparing(e -> e.path))
                .collect(Collectors.toList());
        return new ZipIndex(all, byPath, children, rejected);
    }
}
