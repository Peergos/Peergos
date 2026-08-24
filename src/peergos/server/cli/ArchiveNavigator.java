package peergos.server.cli;

import peergos.server.simulation.FileSystem;
import peergos.server.simulation.Stat;
import peergos.shared.user.fs.*;
import peergos.shared.user.fs.archive.*;
import peergos.shared.util.*;

import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Resolves remote paths that descend into a zip archive, e.g. /me/backups/data.zip/logs/first.log
 *
 *  The archive boundary is the first component of a path that is a file rather than a directory, so
 *  descending into one needs no new syntax and no new commands.
 */
public class ArchiveNavigator {

    private static final String ZIP_MIMETYPE = "application/zip";

    private final FileSystem fs;
    // listing an archive is cheap, but not so cheap that every ls of the same archive should repeat it
    private Path cachedPath;
    private long cachedSize;
    private LocalDateTime cachedModified;
    private ZipReader cached;

    public ArchiveNavigator(FileSystem fs) {
        this.fs = fs;
    }

    /** Where a remote path points.
     */
    public static class Target {
        /** The peergos file or directory: the archive itself when the path descends into one. */
        public final Path path;
        public final Stat stat;
        /** The path within the archive, empty when the path is the archive itself. */
        public final String entry;

        public Target(Path path, Stat stat, String entry) {
            this.path = path;
            this.stat = stat;
            this.entry = entry;
        }

        public boolean isArchive() {
            return ArchiveNavigator.isArchive(stat.fileProperties());
        }

        /** Whether this points inside an archive, rather than at the archive file itself.
         */
        public boolean isInArchive() {
            return isArchive() && ! entry.isEmpty();
        }

        public Path fullPath() {
            return entry.isEmpty() ? path : path.resolve(entry);
        }
    }

    public static boolean isArchive(FileProperties props) {
        return ! props.isDirectory && ZIP_MIMETYPE.equals(props.mimeType);
    }

    public Optional<Target> resolve(Path path) {
        Optional<Stat> direct = statOrEmpty(path);
        if (direct.isPresent())
            return Optional.of(new Target(path, direct.get(), ""));

        List<String> descent = new ArrayList<>();
        for (Path ancestor = path; ancestor.getNameCount() > 1; ) {
            descent.add(0, ancestor.getFileName().toString());
            ancestor = ancestor.getParent();
            Optional<Stat> stat = statOrEmpty(ancestor);
            if (! stat.isPresent())
                continue;
            if (! isArchive(stat.get().fileProperties()))
                return Optional.empty();
            return Optional.of(new Target(ancestor, stat.get(), String.join("/", descent)));
        }
        return Optional.empty();
    }

    private Optional<Stat> statOrEmpty(Path path) {
        try {
            return Optional.of(fs.stat(path));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public synchronized ZipReader open(Target target) {
        FileProperties props = target.stat.fileProperties();
        if (cached != null && target.path.equals(cachedPath) && props.size == cachedSize && props.modified.equals(cachedModified))
            return cached;
        ZipReader zip = ZipReader.open(() -> {
            try {
                return Futures.of(fs.reader(target.path));
            } catch (Exception e) {
                return Futures.errored(e);
            }
        }, props.size).join();
        cachedPath = target.path;
        cachedSize = props.size;
        cachedModified = props.modified;
        cached = zip;
        return zip;
    }

    /** The entry a path inside an archive points at.
     */
    public ZipEntry entry(Target target) {
        return open(target).getIndex().get(target.entry)
                .orElseThrow(() -> new IllegalStateException("No such entry in " + target.path + ": " + target.entry));
    }
}
