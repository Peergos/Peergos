package peergos.server.storage;
import java.util.logging.*;

import peergos.server.space.*;
import peergos.server.storage.admin.*;
import peergos.server.util.Logging;
import peergos.shared.cbor.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.storage.*;
import peergos.shared.util.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

public class UserQuotas implements QuotaAdmin {
	private static final Logger LOG = Logging.LOG();

    private static final long RELOAD_PERIOD_MS = 3_600_000;

    private final Path source;
    private final long defaultQuota, maxUsers;
    private Map<String, Long> quotas = new ConcurrentHashMap<>();
    private long lastModified, lastReloaded;
    private final JdbcSpaceRequests spaceRequests;
    private final ContentAddressedStorage dht;
    private final CoreNode core;

    public UserQuotas(Path source,
                      long defaultQuota,
                      long maxUsers,
                      JdbcSpaceRequests spaceRequests,
                      ContentAddressedStorage dht,
                      CoreNode core) {
        this.source = source;
        this.defaultQuota = defaultQuota;
        this.maxUsers = maxUsers;
        this.spaceRequests = spaceRequests;
        this.dht = dht;
        this.core = core;
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

    @Override
    public List<SpaceUsage.LabelledSignedSpaceRequest> getSpaceRequests() {
        return spaceRequests.getSpaceRequests();
    }

    @Override
    public CompletableFuture<Long> getQuota(PublicKeyHash owner, byte[] signedTime) {
        String username = core.getUsername(owner).join();
        return Futures.of(getQuota(username));
    }

    @Override
    public CompletableFuture<Boolean> requestQuota(PublicKeyHash owner, byte[] signedRequest) {
        // check request is valid
        Optional<PublicSigningKey> ownerOpt = dht.getSigningKey(owner).join();
        if (!ownerOpt.isPresent())
            throw new IllegalStateException("Couldn't retrieve owner key!");
        byte[] raw = ownerOpt.get().unsignMessage(signedRequest);
        CborObject cbor = CborObject.fromByteArray(raw);
        SpaceUsage.SpaceRequest req = QuotaControl.SpaceRequest.fromCbor(cbor);
        if (req.utcMillis < System.currentTimeMillis() - 30_000)
            throw new IllegalStateException("Stale auth time in space request!");
        // TODO check user is signed up to this server
        return Futures.of(spaceRequests.addSpaceRequest(req.username, signedRequest));
    }

    @Override
    public void approveSpaceRequest(PublicKeyHash adminIdentity, Multihash instanceIdentity, byte[] signedRequest) {
        try {
            Optional<PublicSigningKey> adminOpt = dht.getSigningKey(adminIdentity).join();
            if (!adminOpt.isPresent())
                throw new IllegalStateException("Couldn't retrieve admin key!");
            byte[] rawFromAdmin = adminOpt.get().unsignMessage(signedRequest);
            SpaceUsage.LabelledSignedSpaceRequest withName = QuotaControl.LabelledSignedSpaceRequest
                    .fromCbor(CborObject.fromByteArray(rawFromAdmin));

            Optional<PublicKeyHash> userOpt = core.getPublicKeyHash(withName.username).join();
            if (! userOpt.isPresent())
                throw new IllegalStateException("Couldn't lookup user key!");

            Optional<PublicSigningKey> userKey = dht.getSigningKey(userOpt.get()).join();
            if (! userKey.isPresent())
                throw new IllegalStateException("Couldn't retrieve user key!");

            CborObject cbor = CborObject.fromByteArray(userKey.get().unsignMessage(withName.signedRequest));
            SpaceUsage.SpaceRequest req = QuotaControl.SpaceRequest.fromCbor(cbor);
            setQuota(req.username, req.bytes);
            removeSpaceRequest(req.username, withName.signedRequest);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void removeSpaceRequest(String username, byte[] unsigned) {
        spaceRequests.removeSpaceRequest(username, unsigned);
    }

    public boolean acceptingSignups() {
        return quotas.size() < maxUsers;
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
