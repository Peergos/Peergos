package peergos.server.sync;

import org.peergos.config.Jsonable;
import peergos.server.net.SyncConfigHandler;
import peergos.server.util.Args;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class SyncConfig implements Jsonable {
    public final List<String> localDirs, remotePaths, links;
    public final List<Boolean> syncLocalDeletes, syncRemoteDeletes;
    public final int maxDownloadParallelism, minFreeSpacePercent;

    public SyncConfig(List<String> localDirs,
                      List<String> remotePaths,
                      List<String> links,
                      List<Boolean> syncLocalDeletes,
                      List<Boolean> syncRemoteDeletes,
                      int maxDownloadParallelism,
                      int minFreeSpacePercent) {
        if (localDirs.size() != remotePaths.size())
            throw new IllegalStateException("Invalid SyncConfig!");
        if (localDirs.size() != links.size())
            throw new IllegalStateException("Invalid SyncConfig!");
        if (localDirs.size() != syncLocalDeletes.size())
            throw new IllegalStateException("Invalid SyncConfig!");
        if (localDirs.size() != syncRemoteDeletes.size())
            throw new IllegalStateException("Invalid SyncConfig!");
        this.localDirs = localDirs;
        this.remotePaths = remotePaths;
        this.links = links;
        this.syncLocalDeletes = syncLocalDeletes;
        this.syncRemoteDeletes = syncRemoteDeletes;
        this.maxDownloadParallelism = maxDownloadParallelism;
        this.minFreeSpacePercent = minFreeSpacePercent;
    }

    public Map<String, Object> toJsonWithoutCaps() {
        LinkedHashMap<String, Object> res = new LinkedHashMap<>();
        List<Object> pairs = new ArrayList<>();
        for (int i = 0; i < localDirs.size(); i++) {
            LinkedHashMap<String, Object> pair = new LinkedHashMap<>();
            pair.put("localpath", localDirs.get(i));
            pair.put("remotepath", remotePaths.get(i));
            String link = links.get(i);
            // only return the link champ label, which is not sensitive, but enough for the owner to delete it
            pair.put("label", link.substring(link.lastIndexOf("/", link.indexOf("#")) + 1, link.indexOf("#")));
            pair.put("syncLocalDeletes", syncLocalDeletes.get(i));
            pair.put("syncRemoteDeletes", syncRemoteDeletes.get(i));
            pairs.add(pair);
        }
        res.put("pairs", pairs);
        return res;
    }

    @Override
    public Map<String, Object> toJson() {
        LinkedHashMap<String, Object> res = new LinkedHashMap<>();
        List<Object> pairs = new ArrayList<>();
        for (int i = 0; i < localDirs.size(); i++) {
            LinkedHashMap<String, Object> pair = new LinkedHashMap<>();
            String rawLocalDir = localDirs.get(i);
            String localDir = isWindows() ? rawLocalDir.replaceAll("\\\\\\\\", "\\\\") : rawLocalDir;
            pair.put("localpath", localDir);
            pair.put("remotepath", remotePaths.get(i));
            pair.put("link", links.get(i));
            pair.put("syncLocalDeletes", syncLocalDeletes.get(i));
            pair.put("syncRemoteDeletes", syncRemoteDeletes.get(i));
            pairs.add(pair);
        }
        res.put("pairs", pairs);
        res.put("maxParallelism", maxDownloadParallelism);
        res.put("minPercentFreeSpace", minFreeSpacePercent);
        return res;
    }

    public static SyncConfig fromJson(Map<String, Object> json) {
        List<Map<String, Object>> jsonList = (List<Map<String, Object>>) json.get("pairs");
        List<String> localDirs = new ArrayList<>();
        List<String> links = new ArrayList<>();
        List<String> remoteDirs = new ArrayList<>();
        List<Boolean> syncLocalDeletes= new ArrayList<>();
        List<Boolean> syncRemoteDeletes= new ArrayList<>();
        for (Map<String, Object> pair : jsonList) {
            localDirs.add((String)pair.get("localpath"));
            links.add((String)pair.get("link"));
            remoteDirs.add((String)pair.get("remotepath"));
            syncLocalDeletes.add((Boolean)pair.get("syncLocalDeletes"));
            syncRemoteDeletes.add((Boolean)pair.get("syncRemoteDeletes"));
        }
        return new SyncConfig(localDirs, remoteDirs, links, syncLocalDeletes, syncRemoteDeletes, (Integer)json.get("maxParallelism"), (Integer)json.get("minPercentFreeSpace"));
    }

    public static List<String> getLinks(Args updated) {
        if (! updated.hasArg("links"))
            return new ArrayList<>();
        return new ArrayList<>(Arrays.asList(updated.getArg("links").split(",")));
    }

    public static List<String> getLocalDirs(Args updated) {
        if (! updated.hasArg("local-dirs"))
            return new ArrayList<>();
        return new ArrayList<>(Arrays.asList(updated.getArg("local-dirs").split(",")));
    }

    public static List<String> getRemotePaths(Args updated) {
        if (updated.hasArg("remote-paths")) {
            return new ArrayList<>(Arrays.asList(updated.getArg("remote-paths").split("//")));
        }
        return Collections.emptyList();
    }

    public static List<Boolean> getSyncLocalDeletes(Args updated) {
        if (! updated.hasArg("sync-local-deletes"))
            return new ArrayList<>();
        return new ArrayList<>(Stream.of(updated.getArg("sync-local-deletes").split(","))
                .map(Boolean::parseBoolean)
                .collect(Collectors.toList()));
    }

    public static List<Boolean> getSyncRemoteDeletes(Args updated) {
        if (! updated.hasArg("sync-remote-deletes"))
            return new ArrayList<>();
        return new ArrayList<>(Stream.of(updated.getArg("sync-remote-deletes").split(","))
                .map(Boolean::parseBoolean)
                .collect(Collectors.toList()));
    }

    public static SyncConfig fromArgs(Args a) {
        return new SyncConfig(getLocalDirs(a),
                getRemotePaths(a),
                getLinks(a),
                getSyncLocalDeletes(a),
                getSyncRemoteDeletes(a),
                a.getInt("max-parallelism", 32),
                a.getInt("min-free-space-percent", 5));
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().startsWith("windows");
    }
}
