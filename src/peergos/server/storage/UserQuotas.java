package peergos.server.storage;
import java.util.logging.*;

import peergos.server.util.Logging;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

public class UserQuotas {
	private static final Logger LOG = Logging.LOG();

    private static final long RELOAD_PERIOD_MS = 3_600_000;

    private Map<String, Long> quotas = new ConcurrentHashMap<>();
    private final long defaultQuota;
    private final Path source;
    private final ForkJoinPool pool = new ForkJoinPool(1);
    private long lastModified, lastReloaded;

    public UserQuotas(Path source, long defaultQuota) {
        this.source = source;
        this.defaultQuota = defaultQuota;
        pool.submit(() -> {
            while (true) {
                try {
                    updateQuotas();
                } catch (Throwable t) {
                    LOG.log(Level.WARNING, t.getMessage(), t);
                }
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {}
            }
        });
    }

    private void updateQuotas() {
        if (! source.toFile().exists())
            return;
        long modified = source.toFile().lastModified();
        long now = System.currentTimeMillis();
        if (modified != lastModified || (now - lastReloaded > RELOAD_PERIOD_MS)) {
            LOG.info("Updating user quotas...");
            lastModified = modified;
            lastReloaded = now;
            Map<String, Long> readQuotas = readUsernamesFromFile();
            quotas.keySet().retainAll(readQuotas.keySet());
            quotas.putAll(readQuotas);
        }
    }

    private static String getUsername(String line) {
        return line.split(" ")[0];
    }

    private static long getQuota(String line) {
        String quota = line.split(" ")[1];
        if (quota.endsWith("t"))
            return Long.parseLong(quota.substring(0, quota.length() - 1)) * 1024 * 1024 * 1024 * 1024;
        if (quota.endsWith("g"))
            return Long.parseLong(quota.substring(0, quota.length() - 1)) * 1024 * 1024 * 1024;
        if (quota.endsWith("m"))
            return Long.parseLong(quota.substring(0, quota.length() - 1)) * 1024 * 1024;
        if (quota.endsWith("k"))
            return Long.parseLong(quota.substring(0, quota.length() - 1)) * 1024;
        return Long.parseLong(quota);
    }

    private Map<String, Long> readUsernamesFromFile() {
        try {
            if (! source.toFile().exists())
                return Collections.emptyMap();
            return Files.lines(source)
                    .map(String::trim)
                    .collect(Collectors.toMap(UserQuotas::getUsername, UserQuotas::getQuota));
        } catch (IOException e) {
            LOG.log(Level.WARNING, e.getMessage(), e);
            return Collections.emptyMap();
        }
    }

    public long quota(String username) {
        return quotas.getOrDefault(username, defaultQuota);
    }
}
