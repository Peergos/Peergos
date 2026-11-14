package peergos.server.storage;
import java.time.LocalDateTime;
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
    private CoreNode core;
    private final boolean isPki;

    public UserQuotas(JdbcQuotas quotas,
                      long defaultQuota,
                      long maxUsers,
                      JdbcSpaceRequests spaceRequests,
                      ContentAddressedStorage dht,
                      boolean isPki) {
        this.quotas = quotas;
        this.defaultQuota = defaultQuota;
        this.maxUsers = maxUsers;
        this.spaceRequests = spaceRequests;
        this.dht = dht;
        this.isPki = isPki;
    }

    @Override
    public void setPki(CoreNode core) {
        this.core = core;
    }

    @Override
    public List<QuotaControl.LabelledSignedSpaceRequest> getSpaceRequests() {
        // requests stored before they were checked on arrival may not verify, and would otherwise stay forever
        List<QuotaControl.LabelledSignedSpaceRequest> valid = new ArrayList<>();
        for (QuotaControl.LabelledSignedSpaceRequest req : spaceRequests.getSpaceRequests()) {
            if (isValid(req))
                valid.add(req);
            else {
                LOG.info("Removing invalid space request labelled " + req.username);
                spaceRequests.removeSpaceRequest(req.username, req.signedRequest);
            }
        }
        return valid;
    }

    private boolean isValid(QuotaControl.LabelledSignedSpaceRequest labelled) {
        try {
            PublicKeyHash identity = core.getPublicKeyHash(labelled.username).join().get();
            PublicSigningKey key = dht.getSigningKey(identity, identity).join().get();
            byte[] raw = key.unsignMessage(labelled.signedRequest, SigningDomain.SPACE_REQUEST).join();
            return QuotaControl.SpaceRequest.fromCbor(CborObject.fromByteArray(raw)).username.equals(labelled.username);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public CompletableFuture<Long> getQuota(PublicKeyHash owner, byte[] signedTime) {
        TimeLimited.isAllowedTime(signedTime, 300, dht, owner);
        String username = core.getUsername(owner).join();
        return Futures.of(getQuota(username));
    }

    @Override
    public boolean hadQuota(String username, LocalDateTime time) {
        return false;
    }

    @Override
    public CompletableFuture<PaymentProperties> requestQuota(String username,
                                                             PublicKeyHash owner,
                                                             byte[] signedRequest,
                                                             long usage,
                                                             boolean pendingSignup) {
        // there are no paid signups here, and owner must be username's current identity in the pki
        if (! core.getPublicKeyHash(username).join().equals(Optional.of(owner)))
            throw new IllegalStateException("Space request for " + username + " not signed by their identity");
        QuotaAdmin.parseQuotaRequest(username, owner, signedRequest, dht);
        if (! quotas.hasUser(username))
            throw new IllegalStateException("User " + username + " is not signed up to this server");
        spaceRequests.replaceSpaceRequest(username, signedRequest);
        return Futures.of(new PaymentProperties(getQuota(username)));
    }

    @Override
    public void approveSpaceRequest(PublicKeyHash adminIdentity, Multihash instanceIdentity, byte[] signedRequest) {
        try {
            Optional<PublicSigningKey> adminOpt = dht.getSigningKey(adminIdentity, adminIdentity).join();
            if (!adminOpt.isPresent())
                throw new IllegalStateException("Couldn't retrieve admin key!");
            byte[] rawFromAdmin = adminOpt.get().unsignMessage(signedRequest, SigningDomain.SPACE_REQUEST_APPROVAL).join();
            SpaceUsage.LabelledSignedSpaceRequest withName = QuotaControl.LabelledSignedSpaceRequest
                    .fromCbor(CborObject.fromByteArray(rawFromAdmin));

            Optional<PublicKeyHash> userOpt = core.getPublicKeyHash(withName.username).join();
            if (! userOpt.isPresent())
                throw new IllegalStateException("Couldn't lookup user key!");

            Optional<PublicSigningKey> userKey = dht.getSigningKey(userOpt.get(), userOpt.get()).join();
            if (! userKey.isPresent())
                throw new IllegalStateException("Couldn't retrieve user key!");

            CborObject cbor = CborObject.fromByteArray(userKey.get().unsignMessage(withName.signedRequest, SigningDomain.SPACE_REQUEST).join());
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
    public PaymentProperties createPaidUser(String username, Optional<PublicKeyHash> signupOwner) {
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
    public List<String> listTokens() {
        return quotas.listTokens();
    }

    @Override
    public boolean removeToken(String token) {
        return quotas.removeToken(token);
    }

    @Override
    public long getQuota(String username) {
        return quotas.getQuota(username);
    }

    @Override
    public void removeQuota(String username) {
        quotas.removeUser(username);
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
