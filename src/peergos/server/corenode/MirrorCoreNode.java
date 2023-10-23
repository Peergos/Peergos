package peergos.server.corenode;

import peergos.server.*;
import peergos.server.login.*;
import peergos.server.net.*;
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
import peergos.shared.social.*;
import peergos.shared.storage.*;
import peergos.shared.storage.auth.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.io.*;
import java.nio.file.*;
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
    private final PublicKeyHash pkiOwnerIdentity;
    private final Multihash ourNodeId, pkiPeerId;
    private final Hasher hasher;

    private volatile CorenodeState state;
    private final Path statePath;
    private volatile boolean running = true;

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
                          Multihash pkiPeerId,
                          PublicKeyHash pkiOwnerIdentity,
                          Path statePath,
                          Hasher hasher) {
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
        this.pkiPeerId = pkiPeerId;
        this.pkiOwnerIdentity = pkiOwnerIdentity;
        this.statePath = statePath;
        this.ourNodeId = ipfs.id().join();
        this.hasher = hasher;
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
            boolean changed = update();
            if (changed)
                saveState();
        } catch (Throwable t) {
            Logging.LOG().log(Level.SEVERE, "Couldn't update mirror pki state: " + t.getMessage(), t);
        }
    }

    private static class CorenodeState implements Cborable {
        private final PublicKeyHash pkiOwnerIdentity, pkiKey;
        private final MaybeMultihash pkiOwnerTarget, pkiKeyTarget;

        private final Map<String, List<UserPublicKeyLink>> chains;
        private final Map<PublicKeyHash, String> reverseLookup;
        private final List<String> usernames;

        public CorenodeState(PublicKeyHash pkiOwnerIdentity,
                             PublicKeyHash pkiKey,
                             MaybeMultihash pkiOwnerTarget,
                             MaybeMultihash pkiKeyTarget,
                             Map<String, List<UserPublicKeyLink>> chains,
                             Map<PublicKeyHash, String> reverseLookup,
                             List<String> usernames) {
            this.pkiOwnerIdentity = pkiOwnerIdentity;
            this.pkiKey = pkiKey;
            this.pkiOwnerTarget = pkiOwnerTarget;
            this.pkiKeyTarget = pkiKeyTarget;
            this.chains = chains;
            this.reverseLookup = reverseLookup;
            this.usernames = usernames;
        }

        public static CorenodeState buildEmpty(PublicKeyHash pkiOwnerIdentity,
                                               PublicKeyHash pkiKey,
                                               MaybeMultihash pkiOwnerTarget,
                                               MaybeMultihash pkiKeyTarget) {
            return new CorenodeState(pkiOwnerIdentity, pkiKey, pkiOwnerTarget, pkiKeyTarget, new HashMap<>(),
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
            res.put("peergosKey", pkiOwnerIdentity);
            res.put("peergosTarget", pkiOwnerTarget);
            res.put("pkiKey", pkiOwnerIdentity);
            res.put("pkiTarget", pkiKeyTarget);

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
            return new CorenodeState(peergosKey, pkiKey, peergosTarget, pkiTarget, chains, reverse, usernames);
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
        if (!exists && pkiNodeIdentity.equals(PublicKeyHash.fromString("z59vuwzfFDp3ZA8ZpnnmHEuMtyA1q34m3Th49DYXQVJntWpxdGrRqXi"))) {
            // copy initial pki state snapshot from jar
            byte[] pkiSnapshot = JarHandler.getAsset("pki-state.cbor", PathUtil.get("/pki"), false).data;
            Files.write(statePath, pkiSnapshot);
        }
        Logging.LOG().info("Reading state from " + statePath + " which exists ? " + exists);
        byte[] data = Files.readAllBytes(statePath);
        CborObject object = CborObject.fromByteArray(data);
        return CorenodeState.fromCbor(object);
    }

    /**
     *
     * @return whether there was a change
     */
    private synchronized boolean update() {
        try {
            byte[] pkiIdPointer = p2pMutable.getPointer(pkiPeerId, pkiOwnerIdentity, pkiOwnerIdentity).join().get();
            PointerUpdate fresh = MutablePointers.parsePointerTarget(pkiIdPointer, pkiOwnerIdentity, pkiOwnerIdentity, ipfs).join();
            MaybeMultihash newPeergosRoot = fresh.updated;

            CommittedWriterData currentPeergosWd = IpfsCoreNode.getWriterData(List.of(pkiPeerId), (Cid)newPeergosRoot.get(), fresh.sequence, ipfs).join();
            PublicKeyHash pkiKey = currentPeergosWd.props.namedOwnedKeys.get("pki").ownedKey;
            if (pkiKey == null)
                throw new IllegalStateException("No pki key on owner: " + pkiOwnerIdentity);

            byte[] newPointer = p2pMutable.getPointer(pkiPeerId, pkiOwnerIdentity, pkiKey).join().get();
            MaybeMultihash currentPkiRoot = MutablePointers.parsePointerTarget(newPointer, pkiOwnerIdentity, pkiKey, ipfs).join().updated;
            CorenodeState current = state;
            if (pkiOwnerIdentity.equals(current.pkiOwnerIdentity) &&
                    newPeergosRoot.equals(current.pkiOwnerTarget) &&
                    pkiKey.equals(current.pkiKey) &&
                    currentPkiRoot.equals(current.pkiKeyTarget))
                return false;

            Logging.LOG().info("Updating pki mirror state... Please wait. This could take a minute or two");
            CorenodeState updated = CorenodeState.buildEmpty(pkiOwnerIdentity, pkiKey, newPeergosRoot, currentPkiRoot);
            updated.load(current);

            // first retrieve all new blocks to be local
            TransactionId tid = transactions.startTransaction(pkiOwnerIdentity);
            List<Multihash> pkiStorageProviders = List.of(pkiPeerId);
            MaybeMultihash currentTree = IpfsCoreNode.getTreeRoot(pkiStorageProviders, current.pkiKeyTarget, ipfs);
            MaybeMultihash updatedTree = IpfsCoreNode.getTreeRoot(pkiStorageProviders, currentPkiRoot, ipfs);
            Consumer<Triple<ByteArrayWrapper, Optional<CborObject.CborMerkleLink>, Optional<CborObject.CborMerkleLink>>> consumer =
                    t -> {
                        Optional<CborObject.CborMerkleLink> newVal = t.right;
                        if (newVal.isPresent()) {
                            transactions.addBlock(newVal.get().target, tid, pkiOwnerIdentity);
                            ipfs.get(pkiStorageProviders, (Cid) newVal.get().target, "", true).join();
                        }
                    };
            IpfsCoreNode.applyToDiff(pkiStorageProviders, currentTree, updatedTree, 0, IpfsCoreNode::keyHash,
                    Collections.emptyList(), Collections.emptyList(),
                    consumer, ChampWrapper.BIT_WIDTH, ipfs, c -> (CborObject.CborMerkleLink)c).get();

            // now update the mappings
            IpfsCoreNode.updateAllMappings(pkiStorageProviders, current.pkiKeyTarget, currentPkiRoot, ipfs, updated.chains,
                    updated.reverseLookup, updated.usernames);

            // 'pin' the new pki version
            Optional<byte[]> existingPointer = rawPointers.getPointer(pkiKey).join();
            rawPointers.setPointer(pkiKey, existingPointer, newPointer).join();
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
                in.login);
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
                                                       Optional<BatWithId> mirrorBat) {
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

            UserSnapshot snapshot = getUserSnapshot(owner, currentLast.claim.storageProviders, p2pMutable, ipfs, hasher)
                    .thenApply(pointers -> new UserSnapshot(pointers,
                            localSocial.getAndParseFollowRequests(owner),
                            batCave.getUserBats(username, new byte[0]).join(),
                            rawAccount.getLoginData(username))).join();
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
            Mirror.mirrorUser(username, Optional.empty(), mirrorBat, this, p2pMutable, null, ipfs, rawPointers, rawAccount, transactions, hasher);
            Map<PublicKeyHash, byte[]> mirrored = Mirror.mirrorUser(username, Optional.empty(), mirrorBat, this, p2pMutable,
                    null, ipfs, rawPointers, rawAccount, transactions, hasher);

            // Proxy call to their current storage server
            UserSnapshot res = writeTarget.migrateUser(username, newChain, currentStorageId, mirrorBat).join();
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

            // Make sure usage is updated
            SpaceCheckingKeyFilter.processCorenodeEvent(username, owner, usageStore, ipfs, p2pMutable, hasher);
            return Futures.of(res);
        } else // Proxy call to their target storage server
            return writeTarget.migrateUser(username, newChain, migrationTargetNode, mirrorBat);
    }

    @Override
    public CompletableFuture<String> getUsername(PublicKeyHash key) {
        String username = state.reverseLookup.get(key);
        if (username != null)
            return CompletableFuture.completedFuture(username);
        update();
        return CompletableFuture.completedFuture(state.reverseLookup.get(key));
    }

    @Override
    public CompletableFuture<List<String>> getUsernames(String prefix) {
        return CompletableFuture.completedFuture(state.usernames);
    }

    @Override
    public void close() {
        running = false;
    }
}
