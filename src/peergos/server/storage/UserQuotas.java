package peergos.server.storage;
import java.util.logging.*;

import peergos.server.space.*;
import peergos.server.storage.admin.*;
import peergos.server.util.*;
import peergos.shared.cbor.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.storage.*;
import peergos.shared.storage.controller.*;
import peergos.shared.util.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

/** This implements a quota manager for Peergos instances that are not charging for storage
 */
public class UserQuotas implements QuotaAdmin {
	private static final Logger LOG = Logging.LOG();

    private final long defaultQuota, maxUsers;
    private final JdbcQuotas quotas;
    private final JdbcSpaceRequests spaceRequests;
    private final ContentAddressedStorage dht;
    private final CoreNode core;
    private final boolean isPki;

    public UserQuotas(JdbcQuotas quotas,
                      long defaultQuota,
                      long maxUsers,
                      JdbcSpaceRequests spaceRequests,
                      ContentAddressedStorage dht,
                      CoreNode core,
                      boolean isPki) {
        this.quotas = quotas;
        this.defaultQuota = defaultQuota;
        this.maxUsers = maxUsers;
        this.spaceRequests = spaceRequests;
        this.dht = dht;
        this.core = core;
        this.isPki = isPki;
    }

    @Override
    public List<QuotaControl.LabelledSignedSpaceRequest> getSpaceRequests() {
        return spaceRequests.getSpaceRequests();
    }

    @Override
    public CompletableFuture<Long> getQuota(PublicKeyHash owner, byte[] signedTime) {
        TimeLimited.isAllowedTime(signedTime, 300, dht, owner);
        String username = core.getUsername(owner).join();
        return Futures.of(getQuota(username));
    }

    @Override
    public CompletableFuture<PaymentProperties> requestQuota(PublicKeyHash owner, byte[] signedRequest) {
        SpaceUsage.SpaceRequest req = QuotaAdmin.parseQuotaRequest(owner, signedRequest, dht);
        // TODO check user is signed up to this server
        boolean added = spaceRequests.addSpaceRequest(req.username, signedRequest);
        String username = core.getUsername(owner).join();
        return Futures.of(new PaymentProperties(getQuota(username)));
    }

    @Override
    public void approveSpaceRequest(PublicKeyHash adminIdentity, Multihash instanceIdentity, byte[] signedRequest) {
        try {
            Optional<PublicSigningKey> adminOpt = dht.getSigningKey(adminIdentity, adminIdentity).join();
            if (!adminOpt.isPresent())
                throw new IllegalStateException("Couldn't retrieve admin key!");
            byte[] rawFromAdmin = adminOpt.get().unsignMessage(signedRequest).join();
            SpaceUsage.LabelledSignedSpaceRequest withName = QuotaControl.LabelledSignedSpaceRequest
                    .fromCbor(CborObject.fromByteArray(rawFromAdmin));

            Optional<PublicKeyHash> userOpt = core.getPublicKeyHash(withName.username).join();
            if (! userOpt.isPresent())
                throw new IllegalStateException("Couldn't lookup user key!");

            Optional<PublicSigningKey> userKey = dht.getSigningKey(userOpt.get(), userOpt.get()).join();
            if (! userKey.isPresent())
                throw new IllegalStateException("Couldn't retrieve user key!");

            CborObject cbor = CborObject.fromByteArray(userKey.get().unsignMessage(withName.signedRequest).join());
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

    @Override
    public AllowedSignups acceptingSignups() {
        return new AllowedSignups(quotas.numberOfUsers() < maxUsers, false);
    }

    @Override
    public List<String> getLocalUsernames() {
        return new ArrayList<>(quotas.getQuotas().keySet());
    }

    @Override
    public boolean allowSignupOrUpdate(String username, String token) {
        if (quotas.hasUser(username))
            return true;
        if (quotas.hasToken(token))
            return true;
        if (quotas.numberOfUsers() >= maxUsers)
            return false;
        quotas.setQuota(username, defaultQuota);
        return true;
    }

    @Override
    public PaymentProperties createPaidUser(String username) {
        if (isPki)
            return new PaymentProperties(0);
        throw new IllegalStateException("Cannot create a paid user on an unpaid server!");
    }

    @Override
    public void removeDesiredQuota(String username) {}

    @Override
    public boolean consumeToken(String username, String token) {
        if (! token.isEmpty()) {
            return quotas.removeToken(token) && quotas.setQuota(username, defaultQuota);
        }
        return false;
    }

    @Override
    public boolean addToken(String token) {
        return quotas.addToken(token);
    }

    @Override
    public long getQuota(String username) {
        return quotas.getQuota(username);
    }

    @Override
    public CompletableFuture<PaymentProperties> getPaymentProperties(PublicKeyHash owner,
                                                                     boolean newClientSecret,
                                                                     byte[] signedTime) {
        TimeLimited.isAllowedTime(signedTime, 300, dht, owner);
        String username = core.getUsername(owner).join();
        return Futures.of(new PaymentProperties(getQuota(username)));
    }

    public void setQuota(String username, long quota) {
        quotas.setQuota(username, quota);
    }

    private static String getUsername(String line) {
        return line.split(" ")[0];
    }

    private static long parseQuotaLine(String line) {
        return parseQuota(line.split(" ")[1]);
    }

    public static long parseQuota(String quota) {
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

    public static Map<String, Long> readUsernamesFromFile(Path source) {
        try {
            if (! source.toFile().exists())
                return Collections.emptyMap();
            return Files.lines(source)
                    .map(String::trim)
                    .collect(Collectors.toMap(UserQuotas::getUsername, UserQuotas::parseQuotaLine));
        } catch (IOException e) {
            LOG.log(Level.WARNING, e.getMessage(), e);
            return Collections.emptyMap();
        }
    }
}
