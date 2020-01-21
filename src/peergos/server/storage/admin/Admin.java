package peergos.server.storage.admin;

import peergos.server.*;
import peergos.server.space.*;
import peergos.server.storage.*;
import peergos.server.util.*;
import peergos.shared.cbor.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.storage.*;
import peergos.shared.storage.controller.*;
import peergos.shared.util.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class Admin implements InstanceAdmin {

    private static final Path waitingList = Paths.get("waiting-list.txt");
    private static final int MAX_WAITING = 1_000_000;

    private final Set<String> adminUsernames;
    private final JdbcSpaceRequests spaceRequests;
    private final UserQuotas quotas;
    private final CoreNode core;
    private final ContentAddressedStorage ipfs;
    private final AtomicLong lastPendingRequestTime = new AtomicLong(System.currentTimeMillis());
    private final boolean enableWaitList;
    private int numberWaiting;

    public Admin(Set<String> adminUsernames,
                 JdbcSpaceRequests spaceRequests,
                 UserQuotas quotas,
                 CoreNode core,
                 ContentAddressedStorage ipfs,
                 boolean enableWaitList) {
        this.adminUsernames = adminUsernames;
        this.spaceRequests = spaceRequests;
        this.quotas = quotas;
        this.core = core;
        this.ipfs = ipfs;
        this.enableWaitList = enableWaitList;
        try {
            this.numberWaiting = Files.readAllLines(waitingList).size();
        } catch (IOException e) {
            this.numberWaiting = 0;
        }
    }

    @Override
    public CompletableFuture<VersionInfo> getVersionInfo() {
        return CompletableFuture.completedFuture(new VersionInfo(UserService.CURRENT_VERSION));
    }

    @Override
    public synchronized CompletableFuture<List<SpaceUsage.LabelledSignedSpaceRequest>> getPendingSpaceRequests(
            PublicKeyHash adminIdentity,
            Multihash instanceIdentity,
            byte[] signedTime) {
        String username = core.getUsername(adminIdentity).join();
        if (! adminUsernames.contains(username))
            throw new IllegalStateException("User is not an admin on this instance!");
        long time = TimeLimited.isAllowedTime(signedTime, 60, ipfs, adminIdentity);
        if (lastPendingRequestTime.get() >= time)
            throw new IllegalStateException("Replay attack? Stale auth time for getPendingSpaceRequests");
        lastPendingRequestTime.set(time);
        return CompletableFuture.completedFuture(spaceRequests.getSpaceRequests());
    }

    @Override
    public CompletableFuture<Boolean> approveSpaceRequest(PublicKeyHash adminIdentity,
                                                          Multihash instanceIdentity,
                                                          byte[] signedRequest) {
        try {
            // check admin key is from an admin
            String username = core.getUsername(adminIdentity).join();
            if (! adminUsernames.contains(username))
                throw new IllegalStateException("User is not an admin on this instance!");
            Optional<PublicSigningKey> adminOpt = ipfs.getSigningKey(adminIdentity).join();
            if (!adminOpt.isPresent())
                throw new IllegalStateException("Couldn't retrieve admin key!");
            byte[] rawFromAdmin = adminOpt.get().unsignMessage(signedRequest);
            SpaceUsage.LabelledSignedSpaceRequest withName = SpaceUsage.LabelledSignedSpaceRequest
                    .fromCbor(CborObject.fromByteArray(rawFromAdmin));

            Optional<PublicKeyHash> userOpt = core.getPublicKeyHash(withName.username).join();
            if (! userOpt.isPresent())
                throw new IllegalStateException("Couldn't lookup user key!");

            Optional<PublicSigningKey> userKey = ipfs.getSigningKey(userOpt.get()).join();
            if (! userKey.isPresent())
                throw new IllegalStateException("Couldn't retrieve user key!");

            CborObject cbor = CborObject.fromByteArray(userKey.get().unsignMessage(withName.signedRequest));
            SpaceUsage.SpaceRequest req = SpaceUsage.SpaceRequest.fromCbor(cbor);
            quotas.setQuota(req.username, req.bytes);
            return CompletableFuture.completedFuture(spaceRequests.removeSpaceRequest(req.username, withName.signedRequest));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public CompletableFuture<Boolean> acceptingSignups() {
        return Futures.of(quotas.acceptingSignups());
    }

    @Override
    public synchronized CompletableFuture<Boolean> addToWaitList(String email) {
        if (! enableWaitList || numberWaiting >= MAX_WAITING)
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
