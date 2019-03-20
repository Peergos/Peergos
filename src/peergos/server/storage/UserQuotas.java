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

    private final Path source;
    private final long defaultQuota, maxUsers;
    private Map<String, Long> quotas = new ConcurrentHashMap<>();
    private long lastModified, lastReloaded;

    public UserQuotas(Path source, long defaultQuota, long maxUsers) {
        this.source = source;
        this.defaultQuota = defaultQuota;
        this.maxUsers = maxUsers;
        updateQuotas();
        new ForkJoinPool(1).submit(() -> {
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

    public List<String> getLocalUsernames() {
        return new ArrayList<>(quotas.keySet());
    }

    public boolean allowSignupOrUpdate(String username) {
        if (quotas.containsKey(username))
            return true;
        if (quotas.size() >= maxUsers)
            return false;
        quotas.put(username, defaultQuota);
        try {
            saveToFile();
            return true;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public long getQuota(String username) {
        return quotas.getOrDefault(username, defaultQuota);
    }

    public void setQuota(String username, long quota) {
        quotas.put(username, quota);
        try {
            saveToFile();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void saveToFile() throws IOException {
        Files.write(source, quotas.entrySet()
                .stream()
                .sorted(Comparator.comparing(e -> e.getKey()))
                .map(e -> e.getKey() + " " + e.getValue())
                .collect(Collectors.toList()), StandardOpenOption.CREATE);
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

    private static long parseQuota(String line) {
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
                    .collect(Collectors.toMap(UserQuotas::getUsername, UserQuotas::parseQuota));
        } catch (IOException e) {
            LOG.log(Level.WARNING, e.getMessage(), e);
            return Collections.emptyMap();
        }
    }
}
