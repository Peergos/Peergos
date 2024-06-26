package peergos.server.corenode;

import peergos.server.*;
import peergos.server.login.*;
import peergos.server.space.*;
import peergos.server.storage.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.hamt.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.mutable.*;
import peergos.shared.resolution.*;
import peergos.shared.social.*;
import peergos.shared.storage.*;
import peergos.shared.storage.auth.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.logging.*;
import java.util.stream.*;

public class MirrorCoreNode implements CoreNode {

    private static final Logger LOG = Logging.LOG();

    private final CoreNode writeTarget;
    private final JdbcAccount rawAccount;
    private final BatCave batCave;
    private final Account account;
    private final MutablePointersProxy p2pMutable;
    private final DeletableContentAddressedStorage ipfs;
    private final JdbcIpnsAndSocial rawPointers;
    private final MutablePointers localPointers;
    private final TransactionStore transactions;
    private final JdbcIpnsAndSocial localSocial;
    private final UsageStore usageStore;
    private final LinkRetrievalCounter linkCounts;
    private final PublicKeyHash pkiOwnerIdentity;
    private final Multihash ourNodeId, pkiPeerId;
    private final Hasher hasher;
    private final Crypto crypto;

    private volatile CorenodeState state;
    private final Path statePath;
    private volatile boolean running = true, initialized = false;

    public MirrorCoreNode(CoreNode writeTarget,
                          JdbcAccount rawAccount,
                          BatCave batCave,
                          Account account,
                          MutablePointersProxy p2pMutable,
                          DeletableContentAddressedStorage ipfs,
                          JdbcIpnsAndSocial rawPointers,
                          MutablePointers localPointers,
                          TransactionStore transactions,
                          JdbcIpnsAndSocial localSocial,
                          UsageStore usageStore,
                          LinkRetrievalCounter linkCounts,
                          Multihash pkiPeerId,
                          PublicKeyHash pkiOwnerIdentity,
                          Path statePath,
                          Crypto crypto) {
        this.writeTarget = writeTarget;
        this.rawAccount = rawAccount;
        this.batCave = batCave;
        this.account = account;
        this.p2pMutable = p2pMutable;
        this.ipfs = ipfs;
        this.rawPointers = rawPointers;
        this.localPointers = localPointers;
        this.transactions = transactions;
        this.localSocial = localSocial;
        this.usageStore = usageStore;
        this.linkCounts = linkCounts;
        this.pkiPeerId = pkiPeerId;
        this.pkiOwnerIdentity = pkiOwnerIdentity;
        this.statePath = statePath;
        this.ourNodeId = ipfs.id().join();
        this.hasher = crypto.hasher;
        this.crypto = crypto;
        try {
            this.state = load(statePath, pkiOwnerIdentity);
        } catch (IOException e) {
            Logging.LOG().info("No previous pki state file present");
            // load empty
            this.state = CorenodeState.buildEmpty(pkiOwnerIdentity, pkiOwnerIdentity, MaybeMultihash.empty(), MaybeMultihash.empty());
        }
    }

    @Override
    public void initialize() {
        try {
            // first mirror pki blocks locally
            if (state.usernames.isEmpty()) {
                long t1 = System.currentTimeMillis();
                CorenodeRoots remote = getPkiState().left;
                List<Multihash> pkiStorageProviders = List.of(pkiPeerId);
                TransactionId tid = transactions.startTransaction(pkiOwnerIdentity);
                try {
                    MaybeMultihash currentTree = IpfsCoreNode.getTreeRoot(pkiStorageProviders, remote.pkiKeyTarget, ipfs);
                    ipfs.mirror(pkiOwnerIdentity, pkiStorageProviders, Optional.empty(), currentTree.toOptional().map(m -> (Cid)m),
                            Optional.empty(), ipfs.id().join(), x -> {}, tid, hasher).join();
                } finally {
                    transactions.closeTransaction(pkiOwnerIdentity, tid);
                }
                long t2 = System.currentTimeMillis();
                System.out.println("PKI MIRROR TOOK " + (t2-t1)/1000);
            }
            boolean changed = update();
            if (changed)
                saveState();
            initialized = true;
        } catch (Throwable t) {
            Logging.LOG().log(Level.SEVERE, "Couldn't update mirror pki state: " + t.getMessage(), t);
        }
    }

    private static class CorenodeRoots {
        final PublicKeyHash pkiOwnerIdentity, pkiKey;
        final MaybeMultihash pkiOwnerTarget, pkiKeyTarget;

        public CorenodeRoots(PublicKeyHash pkiOwnerIdentity, PublicKeyHash pkiKey, MaybeMultihash pkiOwnerTarget, MaybeMultihash pkiKeyTarget) {
            this.pkiOwnerIdentity = pkiOwnerIdentity;
            this.pkiKey = pkiKey;
            this.pkiOwnerTarget = pkiOwnerTarget;
            this.pkiKeyTarget = pkiKeyTarget;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            CorenodeRoots that = (CorenodeRoots) o;
            return pkiOwnerIdentity.equals(that.pkiOwnerIdentity) && pkiKey.equals(that.pkiKey) && pkiOwnerTarget.equals(that.pkiOwnerTarget) && pkiKeyTarget.equals(that.pkiKeyTarget);
        }

        @Override
        public int hashCode() {
            return Objects.hash(pkiOwnerIdentity, pkiKey, pkiOwnerTarget, pkiKeyTarget);
        }
    }

    private static class CorenodeState implements Cborable {
        private final CorenodeRoots roots;

        private final Map<String, List<UserPublicKeyLink>> chains;
        private final Map<PublicKeyHash, String> reverseLookup;
        private final List<String> usernames;

        public CorenodeState(CorenodeRoots roots,
                             Map<String, List<UserPublicKeyLink>> chains,
                             Map<PublicKeyHash, String> reverseLookup,
                             List<String> usernames) {
            this.roots = roots;
            this.chains = chains;
            this.reverseLookup = reverseLookup;
            this.usernames = usernames;
        }

        public static CorenodeState buildEmpty(PublicKeyHash pkiOwnerIdentity,
                                               PublicKeyHash pkiKey,
                                               MaybeMultihash pkiOwnerTarget,
                                               MaybeMultihash pkiKeyTarget) {
            return new CorenodeState(new CorenodeRoots(pkiOwnerIdentity, pkiKey, pkiOwnerTarget, pkiKeyTarget), new HashMap<>(),
                    new HashMap<>(), new ArrayList<>());
        }

        public void load(CorenodeState other) {
            chains.putAll(other.chains);
            reverseLookup.putAll(other.reverseLookup);
            usernames.clear();
            usernames.addAll(other.usernames);
        }

        @Override
        public CborObject toCbor() {
            Map<String, Cborable> res = new TreeMap<>();
            res.put("peergosKey", roots.pkiOwnerIdentity);
            res.put("peergosTarget", roots.pkiOwnerTarget);
            res.put("pkiKey", roots.pkiOwnerIdentity);
            res.put("pkiTarget", roots.pkiKeyTarget);

            TreeMap<String, Cborable> chainsMap = chains.entrySet()
                .stream()
                .collect(Collectors.toMap(
                    e -> e.getKey(),
                    e -> new CborObject.CborList(e.getValue()),
                    (a,b) -> a,
                    TreeMap::new
                ));
            res.put("chains", CborObject.CborMap.build(chainsMap));
            TreeMap<CborObject, Cborable> reverseMap = reverseLookup.entrySet()
                .stream()
                .collect(Collectors.toMap(
                    e -> e.getKey().toCbor(),
                    e -> new CborObject.CborString(e.getValue()),
                    (a,b) -> a,
                    TreeMap::new
                ));
            res.put("reverse", new CborObject.CborList(reverseMap));
            res.put("usernames", new CborObject.CborList(usernames.stream()
                    .map(CborObject.CborString::new)
                    .collect(Collectors.toList())));

            return CborObject.CborMap.build(res);
        }

        public static CorenodeState fromCbor(CborObject cbor) {
            CborObject.CborMap map = (CborObject.CborMap) cbor;
            PublicKeyHash peergosKey = map.get("peergosKey", PublicKeyHash::fromCbor);
            PublicKeyHash pkiKey = map.get("pkiKey", PublicKeyHash::fromCbor);
            MaybeMultihash peergosTarget = map.get("peergosTarget", MaybeMultihash::fromCbor);
            MaybeMultihash pkiTarget = map.get("pkiTarget", MaybeMultihash::fromCbor);

            Function<Cborable, String> fromString = e -> ((CborObject.CborString) e).value;
            Function<? super Cborable, List<UserPublicKeyLink>> chainParser =
                    c -> ((CborObject.CborList) c).map(UserPublicKeyLink::fromCbor);
            Map<String, List<UserPublicKeyLink>> chains = ((CborObject.CborMap)map.get("chains"))
                    .toMap(fromString, chainParser);

            Map<PublicKeyHash, String> reverse = ((CborObject.CborList)map.get("reverse"))
                    .getMap(PublicKeyHash::fromCbor, fromString);

            List<String> usernames = map.getList("usernames", fromString);
            return new CorenodeState(new CorenodeRoots(peergosKey, pkiKey, peergosTarget, pkiTarget), chains, reverse, usernames);
        }
    }

    public void start() {
        running = true;
        new Thread(() -> {
            while (running) {
                try {
                    Thread.sleep(60_000);
                    boolean changed = update();
                    if (changed)
                        saveState();
                } catch (Throwable t) {
                    Logging.LOG().log(Level.SEVERE, t.getMessage(), t);
                }
            }
        }, "Mirroring PKI node").start();
    }

    private synchronized void saveState() {
        byte[] serialized = state.toCbor().serialize();
        Logging.LOG().info("Writing "+ serialized.length +" bytes to "+ statePath);
        try {
            Files.write(
                    statePath,
                    serialized,
                    StandardOpenOption.CREATE);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static CorenodeState load(Path statePath, PublicKeyHash pkiNodeIdentity) throws IOException {
        boolean exists = Files.exists(statePath);
        Logging.LOG().info("Reading state from " + statePath + " which exists ? " + exists);
        byte[] data = Files.readAllBytes(statePath);
        CborObject object = CborObject.fromByteArray(data);
        return CorenodeState.fromCbor(object);
    }

    private Pair<CorenodeRoots, byte[]> getPkiState() {
        byte[] pkiIdPointer = p2pMutable.getPointer(pkiPeerId, pkiOwnerIdentity, pkiOwnerIdentity).join().get();
        PointerUpdate fresh = MutablePointers.parsePointerTarget(pkiIdPointer, pkiOwnerIdentity, pkiOwnerIdentity, ipfs).join();
        MaybeMultihash newPeergosRoot = fresh.updated;

        CommittedWriterData currentPeergosWd = IpfsCoreNode.getWriterData(List.of(pkiPeerId), (Cid)newPeergosRoot.get(), fresh.sequence, ipfs).join();
        PublicKeyHash pkiKey = currentPeergosWd.props.get().namedOwnedKeys.get("pki").ownedKey;
        if (pkiKey == null)
            throw new IllegalStateException("No pki key on owner: " + pkiOwnerIdentity);

        byte[] newPointer = p2pMutable.getPointer(pkiPeerId, pkiOwnerIdentity, pkiKey).join().get();
        PointerUpdate pkiUpdateCas = MutablePointers.parsePointerTarget(newPointer, pkiOwnerIdentity, pkiKey, ipfs).join();
        MaybeMultihash currentPkiRoot = pkiUpdateCas.updated;
        return new Pair<>(new CorenodeRoots(pkiOwnerIdentity, pkiKey, newPeergosRoot, currentPkiRoot), newPointer);
    }

    /**
     *
     * @return whether there was a change
     */
    private synchronized boolean update() {
        try {
            Pair<CorenodeRoots, byte[]> remoteState = getPkiState();
            CorenodeRoots remote = remoteState.left;
            CorenodeState current = state;
            if (remote.pkiOwnerIdentity.equals(current.roots.pkiOwnerIdentity) &&
                    remote.pkiOwnerTarget.equals(current.roots.pkiOwnerTarget) &&
                    remote.pkiKey.equals(current.roots.pkiKey) &&
                    remote.pkiKeyTarget.equals(current.roots.pkiKeyTarget))
                return false;

            Logging.LOG().info("Updating pki mirror state... Please wait. This could take a minute or two");
            CorenodeState updated = CorenodeState.buildEmpty(pkiOwnerIdentity, remote.pkiKey, remote.pkiOwnerTarget, remote.pkiKeyTarget);
            updated.load(current);

            // first retrieve all new blocks to be local
            TransactionId tid = transactions.startTransaction(pkiOwnerIdentity);
            List<Multihash> pkiStorageProviders = List.of(pkiPeerId);
            MaybeMultihash currentTree = IpfsCoreNode.getTreeRoot(pkiStorageProviders, current.roots.pkiKeyTarget, ipfs);
            MaybeMultihash updatedTree = IpfsCoreNode.getTreeRoot(pkiStorageProviders, remote.pkiKeyTarget, ipfs);

            // explicitly get other direct blocks, in theory need recursive mirror, but this is complete here
            if (updatedTree.isPresent()) {
                CommittedWriterData currentWd = IpfsCoreNode.getWriterData(pkiStorageProviders,
                        (Cid) remote.pkiKeyTarget.get(), Optional.empty(), ipfs).join();
                List<Multihash> toAdd = currentWd.props.get().toCbor().links().stream()
                        .filter(h -> updatedTree.map(m -> !m.equals(h)).orElse(true))
                        .collect(Collectors.toList());
                for (Multihash m : toAdd) {
                    ipfs.get(pkiStorageProviders, (Cid) m, "").join();
                }
            }

            Consumer<Triple<ByteArrayWrapper, Optional<CborObject.CborMerkleLink>, Optional<CborObject.CborMerkleLink>>> consumer =
                    t -> {
                        Optional<CborObject.CborMerkleLink> newVal = t.right;
                        if (newVal.isPresent()) {
                            transactions.addBlock(newVal.get().target, tid, pkiOwnerIdentity);
                            ipfs.get(pkiStorageProviders, (Cid) newVal.get().target, "").join();
                        }
                    };
            IpfsCoreNode.applyToDiff(pkiStorageProviders, currentTree, updatedTree, 0, IpfsCoreNode::keyHash,
                    Collections.emptyList(), Collections.emptyList(),
                    consumer, ChampWrapper.BIT_WIDTH, ipfs, c -> (CborObject.CborMerkleLink)c).get();

            // now update the mappings
            IpfsCoreNode.updateAllMappings(pkiStorageProviders, current.roots.pkiKeyTarget, remote.pkiKeyTarget, ipfs, updated.chains,
                    updated.reverseLookup, updated.usernames);

            // 'pin' the new pki version
            Optional<byte[]> existingPointer = rawPointers.getPointer(remote.pkiKey).join();
            rawPointers.setPointer(remote.pkiKey, existingPointer, remoteState.right).join();
            transactions.closeTransaction(pkiOwnerIdentity, tid);

            state = updated;
            Logging.LOG().info("... finished updating pki mirror state.");
            return true;
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public CompletableFuture<Optional<RequiredDifficulty>> signup(String username,
                                                                  UserPublicKeyLink chain,
                                                                  OpLog setupOperations,
                                                                  ProofOfWork proof,
                                                                  String token) {
        Optional<RequiredDifficulty> pkiResult = writeTarget.updateChain(username, Arrays.asList(chain), proof, token).join();
        if (pkiResult.isPresent()) { // signup rejected
            LOG.info("Rejecting signup: required " + pkiResult.get());
            AggregatedMetrics.PKI_RATE_LIMITED.inc();
            return Futures.of(pkiResult);
        }

        if (initialized)
            update();
        usageStore.addUserIfAbsent(username);
        usageStore.addWriter(username, chain.owner);
        IpfsCoreNode.applyOpLog(username, chain.owner, setupOperations, ipfs, localPointers, account, batCave);
        return Futures.of(Optional.empty());
    }

    @Override
    public CompletableFuture<Either<PaymentProperties, RequiredDifficulty>> startPaidSignup(String username,
                                                                                            UserPublicKeyLink chain,
                                                                                            ProofOfWork proof) {
        return writeTarget.startPaidSignup(username, chain, proof);
    }

    @Override
    public CompletableFuture<PaymentProperties> completePaidSignup(String username,
                                                                   UserPublicKeyLink chain,
                                                                   OpLog setupOperations,
                                                                   byte[] signedSpaceRequest,
                                                                   ProofOfWork proof) {
        usageStore.addUserIfAbsent(username);
        usageStore.addWriter(username, chain.owner);
        long t0 = System.currentTimeMillis();
        IpfsCoreNode.applyOpLog(username, chain.owner, setupOperations, ipfs, localPointers, account, batCave);
        long t1 = System.currentTimeMillis();
        writeTarget.completePaidSignup(username, chain, setupOperations, signedSpaceRequest, proof).join();
        long t2 = System.currentTimeMillis();
        LOG.info("Complete Paid signup timing - oplog: " + (t1-t0) + "ms, pki confirmation: " + (t2-t1) + "ms");
        new Thread(() -> {
            try {
                update();
            } catch (Throwable t) {
                LOG.log(Level.WARNING, "Failed to update local PKI during completePaidSignup", t);
            }
        }).start();
        return Futures.of(new PaymentProperties(0));
    }

    @Override
    public CompletableFuture<List<UserPublicKeyLink>> getChain(String username) {
        List<UserPublicKeyLink> chain = state.chains.get(username);
        if (chain != null)
            return CompletableFuture.completedFuture(chain);
        if (! initialized)
            return writeTarget.getChain(username);

        update();
        return CompletableFuture.completedFuture(state.chains.getOrDefault(username, Collections.emptyList()));
    }

    @Override
    public CompletableFuture<Optional<RequiredDifficulty>> updateChain(String username,
                                                                       List<UserPublicKeyLink> chain,
                                                                       ProofOfWork proof,
                                                                       String token) {
        return writeTarget.updateChain(username, chain, proof, token)
                .thenApply(x -> {
                    if (x.isEmpty())
                        this.update();
                    return x;
                });
    }

    private UserSnapshot update(UserSnapshot in) {
        return new UserSnapshot(in.pointerState.entrySet().stream()
                .flatMap(e -> rawPointers.getPointer(e.getKey()).join()
                        .map(v -> new Pair<>(e.getKey(), v))
                        .stream())
                .collect(Collectors.toMap(p -> p.left, p -> p.right)),
                in.pendingFollowReqs,
                in.mirrorBats,
                in.login,
                in.linkCounts);
    }

    public static CompletableFuture<Map<PublicKeyHash, byte[]>> getUserSnapshotRecursive(List<Multihash> peerIds,
                                                                                         PublicKeyHash owner,
                                                                                         PublicKeyHash writer,
                                                                                         Map<PublicKeyHash, byte[]> alreadyDone,
                                                                                         MutablePointers mutable,
                                                                                         DeletableContentAddressedStorage ipfs,
                                                                                         Hasher hasher) {
        return DeletableContentAddressedStorage.getDirectOwnedKeys(owner, writer, mutable,
                        (h, s) -> DeletableContentAddressedStorage.getWriterData(peerIds, h, s, ipfs), ipfs, hasher)
                .thenCompose(directOwned -> {
                    Set<PublicKeyHash> newKeys = directOwned.stream().
                            filter(h -> ! alreadyDone.containsKey(h))
                            .collect(Collectors.toSet());
                    Map<PublicKeyHash, byte[]> done = new HashMap<>(alreadyDone);
                    return mutable.getPointer(owner, writer).thenCompose(val -> {
                        if (val.isPresent())
                            done.put(writer, val.get());
                        BiFunction<Map<PublicKeyHash, byte[]>, PublicKeyHash,
                                CompletableFuture<Map<PublicKeyHash, byte[]>>> composer =
                                (a, w) -> getUserSnapshotRecursive(peerIds, owner, w, a, mutable, ipfs, hasher)
                                        .thenApply(ws ->
                                                Stream.concat(
                                                                ws.entrySet().stream().filter(e -> ! a.containsKey(e.getKey())),
                                                                a.entrySet().stream())
                                                        .collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue())));
                        return Futures.reduceAll(newKeys, done,
                                composer,
                                (a, b) -> Stream.concat(
                                                a.entrySet().stream().filter(e -> ! b.containsKey(e.getKey())),
                                                b.entrySet().stream())
                                        .collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue())));
                    });
                });
    }

    public static CompletableFuture<Map<PublicKeyHash, byte[]>> getUserSnapshot(PublicKeyHash owner,
                                                                                List<Multihash> peerIds,
                                                                                MutablePointers mutable,
                                                                                DeletableContentAddressedStorage dht,
                                                                                Hasher hasher) {
        return getUserSnapshotRecursive(peerIds, owner, owner, Collections.emptyMap(), mutable, dht, hasher);
    }

    @Override
    public CompletableFuture<UserSnapshot> migrateUser(String username,
                                                       List<UserPublicKeyLink> newChain,
                                                       Multihash currentStorageId,
                                                       Optional<BatWithId> mirrorBat,
                                                       LocalDateTime latestLinkCountUpdate,
                                                       long currentUsage) {
        // check chain validity before proceeding further
        List<UserPublicKeyLink> existingChain = getChain(username).join();
        UserPublicKeyLink currentLast = existingChain.get(existingChain.size() - 1);
        UserPublicKeyLink newLast = newChain.get(newChain.size() - 1);
        if (currentLast.claim.expiry.isAfter(newLast.claim.expiry))
            throw new IllegalStateException("Migration claim has earlier expiry than current one!");
        UserPublicKeyLink.merge(existingChain, newChain, ipfs).join();
        Multihash migrationTargetNode = newLast.claim.storageProviders.get(0);
        PublicKeyHash owner = newLast.owner;

        if (currentStorageId.equals(ourNodeId)) {
            // a user is migrating away from this server
            ProofOfWork work = ProofOfWork.empty();
            LinkCounts updated = linkCounts.getUpdatedCounts(username, latestLinkCountUpdate);
            UserSnapshot snapshot = getUserSnapshot(owner, currentLast.claim.storageProviders, p2pMutable, ipfs, hasher)
                    .thenApply(pointers -> new UserSnapshot(pointers,
                            localSocial.getAndParseFollowRequests(owner),
                            batCave.getUserBats(username, new byte[0]).join(),
                            rawAccount.getLoginData(username), updated)).join();
            updateChain(username, newChain, work, "").join();
            // from this point on new writes are proxied to the new storage server
            return Futures.of(update(snapshot));
        }

        if (migrationTargetNode.equals(ourNodeId)) {
            // We are copying data to this node
            // Make sure we have the mirror bat stored in out batStore first
            if (mirrorBat.isPresent()) {
                BatWithId bat = mirrorBat.get();
                // double check it
                if (! BatId.sha256(bat.bat, hasher).join().equals(bat.id()))
                    throw new IllegalStateException("Invalid BAT id for BAT");
                List<BatWithId> localMirrorBats = batCave.getUserBats(username, new byte[0]).join();
                if (! localMirrorBats.contains(bat))
                    batCave.addBat(username, bat.id(), bat.bat, new byte[0]);
            }
            List<Multihash> storageProviders = getStorageProviders(owner);
            // Mirror all the data locally
            Mirror.mirrorUser(username, Optional.empty(), mirrorBat, this, p2pMutable, null, ipfs, rawPointers, rawAccount, transactions, linkCounts, hasher);
            Map<PublicKeyHash, byte[]> mirrored = Mirror.mirrorUser(username, Optional.empty(), mirrorBat, this, p2pMutable,
                    null, ipfs, rawPointers, rawAccount, transactions, linkCounts, hasher);

            // Proxy call to their current storage server
            LocalDateTime localLatestLinkCountTime = linkCounts.getLatestModificationTime(username).orElse(LocalDateTime.MIN);
            UserSnapshot res = writeTarget.migrateUser(username, newChain, currentStorageId, mirrorBat, localLatestLinkCountTime, currentUsage).join();
            // pick up the new pki data locally
            update();

            res.mirrorBats.forEach(b -> {
                batCave.addBat(username, b.id(), b.bat, new byte[0]);
            });
            res.login.ifPresent(rawAccount::setLoginData);

            // commit diff since our mirror above
            for (Map.Entry<PublicKeyHash, byte[]> e : res.pointerState.entrySet()) {
                byte[] existingVal = mirrored.get(e.getKey());
                if (! Arrays.equals(existingVal, e.getValue())) {
                    Mirror.mirrorMerkleTree(owner, e.getKey(), storageProviders, e.getValue(), mirrorBat, ipfs, rawPointers, transactions, hasher);
                }
            }

            // Copy pending follow requests to local server
            for (BlindFollowRequest req : res.pendingFollowReqs) {
                // write directly to local social database to avoid being redirected to user's current node
                localSocial.addFollowRequest(owner, req.serialize()).join();
            }

            // update local link counts
            linkCounts.setCounts(username, res.linkCounts);

            // Make sure usage is updated
            List<Multihash> us = List.of(ourNodeId.bareMultihash());
            Set<PublicKeyHash> allUserKeys = DeletableContentAddressedStorage.getOwnedKeysRecursive(owner, owner, p2pMutable,
                    (h, s) -> DeletableContentAddressedStorage.getWriterData(us, h,s, ipfs), ipfs, hasher).join();
            SpaceCheckingKeyFilter.processCorenodeEvent(username, owner, allUserKeys, usageStore, ipfs, p2pMutable, hasher);
            return Futures.of(res);
        } else // Proxy call to their target storage server
            return writeTarget.migrateUser(username, newChain, migrationTargetNode, mirrorBat, latestLinkCountUpdate, currentUsage);
    }

    @Override
    public CompletableFuture<String> getUsername(PublicKeyHash key) {
        String username = state.reverseLookup.get(key);
        if (username != null)
            return CompletableFuture.completedFuture(username);
        if (! initialized)
            return writeTarget.getUsername(key);
        update();
        return CompletableFuture.completedFuture(state.reverseLookup.get(key));
    }

    @Override
    public CompletableFuture<List<String>> getUsernames(String prefix) {
        if (! state.usernames.isEmpty())
            return CompletableFuture.completedFuture(state.usernames);
        if (! initialized)
            return writeTarget.getUsernames(prefix);
        return CompletableFuture.completedFuture(state.usernames);
    }

    @Override
    public List<Multihash> getStorageProviders(PublicKeyHash owner) {
        String username = getUsername(owner).join();
        List<UserPublicKeyLink> chain = getChain(username).join();
        if (chain.isEmpty())
            return Collections.emptyList();
        List<Multihash> fromPki = chain.get(chain.size() - 1).claim.storageProviders;
        List<Multihash> withIdRotations = fromPki.stream()
                .map(h -> rotatedServers.getOrDefault(h.bareMultihash(), h))
                .collect(Collectors.toList());
        return withIdRotations;
    }

    private final LRUCache<Multihash, Multihash> rotatedServers = new LRUCache<>(100);

    @Override
    public CompletableFuture<Optional<Multihash>> getNextServerId(Multihash serverId) {
        return ipfs.getIpnsEntry(serverId)
                .thenApply(e -> {
                    ResolutionRecord value = e.getValue(serverId, crypto).join();
                    if (value.moved)
                        value.host.ifPresent(newHost -> rotatedServers.put(serverId, new Cid(1, Cid.Codec.LibP2pKey, newHost.type, newHost.getHash())));
                    return value.host;
                });
    }

    @Override
    public void close() {
        running = false;
    }
}
