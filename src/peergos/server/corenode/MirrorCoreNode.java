package peergos.server.corenode;

import peergos.server.storage.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.hamt.*;
import peergos.shared.mutable.*;
import peergos.shared.storage.*;
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

    private final CoreNode writeTarget;
    private final MutablePointers p2pMutable;
    private final ContentAddressedStorage ipfs;
    private final JdbcIpnsAndSocial localPointers;
    private final TransactionStore transactions;
    private final PublicKeyHash pkiOwnerIdentity;

    private volatile CorenodeState state;
    private final Path statePath;
    private volatile boolean running = true;

    public MirrorCoreNode(CoreNode writeTarget,
                          MutablePointers p2pMutable,
                          ContentAddressedStorage ipfs,
                          JdbcIpnsAndSocial localPointers,
                          TransactionStore transactions,
                          PublicKeyHash pkiOwnerIdentity,
                          Path statePath) {
        this.writeTarget = writeTarget;
        this.p2pMutable = p2pMutable;
        this.ipfs = ipfs;
        this.localPointers = localPointers;
        this.transactions = transactions;
        this.pkiOwnerIdentity = pkiOwnerIdentity;
        this.statePath = statePath;
        try {
            this.state = load(statePath);
        } catch (IOException e) {
            // load empty
            this.state = CorenodeState.buildEmpty(pkiOwnerIdentity, pkiOwnerIdentity, MaybeMultihash.empty(), MaybeMultihash.empty());
        }
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

    private static CorenodeState load(Path statePath) throws IOException {
        Logging.LOG().info("Reading state from " + statePath + " which exists ? " + Files.exists(statePath) + " from cwd " + System.getProperty("cwd"));
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
            PublicKeyHash peergosKey = writeTarget.getPublicKeyHash("peergos").join().get();

            MaybeMultihash newPeergosRoot = p2pMutable.getPointerTarget(peergosKey, peergosKey, ipfs).get();

            CommittedWriterData currentPeergosWd = WriterData.getWriterData(newPeergosRoot.get(), ipfs).get();
            PublicKeyHash pkiKey = currentPeergosWd.props.namedOwnedKeys.get("pki").ownedKey;
            if (pkiKey == null)
                throw new IllegalStateException("No pki key on owner: " + pkiOwnerIdentity);

            byte[] newPointer = p2pMutable.getPointer(pkiOwnerIdentity, pkiKey).join().get();
            MaybeMultihash currentPkiRoot = MutablePointers.parsePointerTarget(newPointer, pkiKey, ipfs).join();
            CorenodeState current = state;
            if (peergosKey.equals(current.pkiOwnerIdentity) &&
                    newPeergosRoot.equals(current.pkiOwnerTarget) &&
                    pkiKey.equals(current.pkiKey) &&
                    currentPkiRoot.equals(current.pkiKeyTarget))
                return false;

            Logging.LOG().info("Updating pki mirror state... Please wait. This could take a minute or two");
            CorenodeState updated = CorenodeState.buildEmpty(peergosKey, pkiKey, newPeergosRoot, currentPkiRoot);
            updated.load(current);

            // first retrieve all new blocks to be local
            TransactionId tid = transactions.startTransaction(peergosKey);
            MaybeMultihash currentTree = IpfsCoreNode.getTreeRoot(current.pkiKeyTarget, ipfs);
            MaybeMultihash updatedTree = IpfsCoreNode.getTreeRoot(currentPkiRoot, ipfs);
            Consumer<Triple<ByteArrayWrapper, Optional<CborObject.CborMerkleLink>, Optional<CborObject.CborMerkleLink>>> consumer =
                    t -> {
                        Optional<CborObject.CborMerkleLink> newVal = t.right;
                        if (newVal.isPresent()) {
                            transactions.addBlock(newVal.get().target, tid, peergosKey);
                            ipfs.get(newVal.get().target).join();
                        }
                    };
            Champ.applyToDiff(currentTree, updatedTree, 0, IpfsCoreNode::keyHash,
                    Collections.emptyList(), Collections.emptyList(),
                    consumer, ChampWrapper.BIT_WIDTH, ipfs, c -> (CborObject.CborMerkleLink)c).get();

            // now update the mappings
            IpfsCoreNode.updateAllMappings(pkiKey, current.pkiKeyTarget, currentPkiRoot, ipfs, updated.chains,
                    updated.reverseLookup, updated.usernames);

            // 'pin' the new pki version
            Optional<byte[]> existingPointer = localPointers.getPointer(pkiKey).join();
            localPointers.setPointer(pkiKey, existingPointer, newPointer).join();
            transactions.closeTransaction(peergosKey, tid);

            state = updated;
            Logging.LOG().info("... finished updating pki mirror state.");
            return true;
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
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
