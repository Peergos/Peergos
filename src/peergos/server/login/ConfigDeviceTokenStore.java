package peergos.server.login;

import peergos.server.util.Logging;
import peergos.shared.login.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.*;

/** Keeps device tokens in the Peergos config file, so that a desktop app is still a known device
 *  after it restarts. The file is reread on every lookup, because the other processes of a desktop
 *  install (sync, fuse, the local server) share it.
 */
public class ConfigDeviceTokenStore implements DeviceTokenStore {
    private static final Logger LOG = Logging.LOG();
    private static final String PREFIX = "device-token.";
    private static final Object LOCK = new Object();

    private final Path config;

    public ConfigDeviceTokenStore(Path config) {
        this.config = config;
    }

    @Override
    public Optional<String> get(String username) {
        String key = PREFIX + username;
        try {
            for (String line : readLines()) {
                int eq = line.indexOf('=');
                if (eq > 0 && line.substring(0, eq).trim().equals(key))
                    return Optional.of(line.substring(eq + 1).trim());
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Couldn't read device token from " + config, e);
        }
        return Optional.empty();
    }

    @Override
    public void set(String username, String token) {
        String key = PREFIX + username;
        String entry = key + " = " + token;
        synchronized (LOCK) {
            try {
                List<String> lines = new ArrayList<>(readLines());
                boolean replaced = false;
                for (int i = 0; i < lines.size(); i++) {
                    int eq = lines.get(i).indexOf('=');
                    if (eq > 0 && lines.get(i).substring(0, eq).trim().equals(key)) {
                        lines.set(i, entry);
                        replaced = true;
                    }
                }
                if (! replaced)
                    lines.add(entry);
                Path parent = config.toAbsolutePath().getParent();
                Files.createDirectories(parent);
                // written whole and then moved, so another process never reads half a config
                Path tmp = Files.createTempFile(parent, "config", ".tmp");
                Files.write(tmp, String.join("\n", lines).getBytes());
                Files.move(tmp, config, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                // the login has still succeeded, this device just won't be recognised next time
                LOG.log(Level.WARNING, "Couldn't save device token to " + config, e);
            }
        }
    }

    private List<String> readLines() throws IOException {
        if (! Files.exists(config))
            return Collections.emptyList();
        return Files.readAllLines(config);
    }
}
