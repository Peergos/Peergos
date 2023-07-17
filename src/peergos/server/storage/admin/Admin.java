package peergos.server.storage.admin;

import peergos.server.*;
import peergos.server.util.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.random.*;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.storage.*;
import peergos.shared.storage.controller.*;
import peergos.shared.util.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.regex.*;

public class Admin implements InstanceAdmin {

    private static final Path waitingList = PathUtil.get("waiting-list.txt");
    private static final int MAX_WAITING = 1_000_000;

    private final Set<String> adminUsernames;
    private final QuotaAdmin quotas;
    private final CoreNode core;
    private final ContentAddressedStorage ipfs;
    private final AtomicLong lastPendingRequestTime = new AtomicLong(System.currentTimeMillis());
    private final boolean enableWaitList;
    private int numberWaiting;
    private final String sourceVersion;

    public Admin(Set<String> adminUsernames,
                 QuotaAdmin quotas,
                 CoreNode core,
                 ContentAddressedStorage ipfs,
                 boolean enableWaitList) {
        this.adminUsernames = adminUsernames;
        this.quotas = quotas;
        this.core = core;
        this.ipfs = ipfs;
        this.enableWaitList = enableWaitList;
        try {
            this.numberWaiting = Files.readAllLines(waitingList).size();
        } catch (IOException e) {
            this.numberWaiting = 0;
        }

        this.sourceVersion = getSourceVersion();
    }

    public static String getSourceVersion() {
        return Optional.ofNullable(Admin.class.getPackage().getImplementationVersion()).orElse("");
    }

    @Override
    public CompletableFuture<VersionInfo> getVersionInfo() {
        return CompletableFuture.completedFuture(new VersionInfo(UserService.CURRENT_VERSION, sourceVersion));
    }

    @Override
    public synchronized CompletableFuture<List<SpaceUsage.LabelledSignedSpaceRequest>> getPendingSpaceRequests(
            PublicKeyHash adminIdentity,
            Multihash instanceIdentity,
            byte[] signedTime) {
        String username = core.getUsername(adminIdentity).join();
        if (! adminUsernames.contains(username))
            return Futures.of(Collections.emptyList());
        long time = TimeLimited.isAllowedTime(signedTime, 60, ipfs, adminIdentity);
        if (lastPendingRequestTime.get() >= time)
            throw new IllegalStateException("Replay attack? Stale auth time for getPendingSpaceRequests");
        lastPendingRequestTime.set(time);
        return CompletableFuture.completedFuture(quotas.getSpaceRequests());
    }

    @Override
    public CompletableFuture<Boolean> approveSpaceRequest(PublicKeyHash adminIdentity,
                                                          Multihash instanceIdentity,
                                                          byte[] signedRequest) {
            // check admin key is from an admin
            String username = core.getUsername(adminIdentity).join();
            if (! adminUsernames.contains(username))
                throw new IllegalStateException("User is not an admin on this instance!");
            quotas.approveSpaceRequest(adminIdentity, instanceIdentity, signedRequest);
            return Futures.of(true);
    }

    @Override
    public CompletableFuture<AllowedSignups> acceptingSignups() {
        return Futures.of(quotas.acceptingSignups());
    }

    public String generateSignupToken(SafeRandom rnd) {
        return quotas.generateToken(rnd);
    }

    private static Pattern VALID_EMAIL = Pattern.compile("^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,6}$", Pattern.CASE_INSENSITIVE);
    private static final int MAX_EMAIL_LENGTH = 256;

    @Override
    public synchronized CompletableFuture<Boolean> addToWaitList(String email) {
        if (! enableWaitList
                || numberWaiting >= MAX_WAITING
                || ! VALID_EMAIL.matcher(email).matches()
                || email.length() > MAX_EMAIL_LENGTH)
            return Futures.of(false);
        try {
            Files.write(waitingList, (email + "\n").getBytes(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            numberWaiting++;
            return Futures.of(true);
        } catch (IOException e) {
            e.printStackTrace();
            return Futures.of(false);
        }
    }
}
