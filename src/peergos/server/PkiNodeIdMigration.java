package peergos.server;

import peergos.server.storage.*;
import peergos.server.util.*;
import peergos.shared.io.ipfs.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.*;
import java.util.stream.*;

/** Saved configs keep the pki-node-id and bootstrap list from when they were written, which override new
 *  defaults. When the pki node rotates its identity, this moves configs naming one of its former ids to the
 *  current one. Configs for any other pki, like test networks, are left alone.
 */
public class PkiNodeIdMigration {
    private static final Logger LOG = Logging.LOG();
    private static final String PKI_NODE_ID = "pki-node-id";

    /**
     * @return whether the config file was changed
     */
    public static boolean migrateConfig(Path config) {
        try {
            return migrateConfig(config, Main.PKI_NODE_IDS, IpfsWrapper.DEFAULT_BOOTSTRAP_LIST);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Couldn't migrate pki node id in " + config, e);
            return false;
        }
    }

    /**
     * @param pkiNodeIds every id the pki node has had, the current one last
     * @param defaultBootstrap the bootstrap list that replaces any saved list naming a former id
     */
    public static boolean migrateConfig(Path config, List<String> pkiNodeIds, String defaultBootstrap) throws IOException {
        if (! config.toFile().exists())
            return false;
        String latest = pkiNodeIds.get(pkiNodeIds.size() - 1);
        Multihash latestId = peerId(latest).get();
        Set<Multihash> former = pkiNodeIds.stream()
                .map(id -> peerId(id).get())
                .filter(id -> ! id.equals(latestId))
                .collect(Collectors.toSet());
        if (former.isEmpty())
            return false;

        List<String> lines = Files.readAllLines(config);
        List<String> updated = new ArrayList<>();
        boolean changed = false;
        for (String line : lines) {
            int eq = line.indexOf('=');
            if (eq < 0 || line.trim().startsWith("#") || line.indexOf('#') > eq) {
                updated.add(line);
                continue;
            }
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            Optional<String> newValue = Optional.empty();
            if (key.equals(PKI_NODE_ID) && peerId(value).map(former::contains).orElse(false))
                newValue = Optional.of(latest);
            else if (key.equals(IpfsWrapper.IPFS_BOOTSTRAP_NODES))
                newValue = Arrays.stream(value.split(",")).anyMatch(e -> isFor(e, former)) ?
                        Optional.of(defaultBootstrap) :
                        Optional.empty();
            if (newValue.isPresent() && ! newValue.get().equals(value)) {
                LOG.info("Updating " + key + " in " + config + " to the pki node's current identity " + latest);
                updated.add(key + " = " + newValue.get());
                changed = true;
            } else
                updated.add(line);
        }
        if (changed)
            Files.write(config, String.join("\n", updated).getBytes(), StandardOpenOption.TRUNCATE_EXISTING);
        return changed;
    }

    private static boolean isFor(String multiaddr, Set<Multihash> ids) {
        int p2p = multiaddr.lastIndexOf("/p2p/");
        if (p2p < 0)
            return false;
        return peerId(multiaddr.substring(p2p + "/p2p/".length())).map(ids::contains).orElse(false);
    }

    private static Optional<Multihash> peerId(String id) {
        try {
            return Optional.of(Cid.decodePeerId(id.trim()).bareMultihash());
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
