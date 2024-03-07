package peergos.shared;
import java.util.function.*;
import java.util.logging.*;

import jsinterop.annotations.*;
import peergos.shared.cbor.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.hamt.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.login.*;
import peergos.shared.mutable.*;
import peergos.shared.social.*;
import peergos.shared.storage.*;
import peergos.shared.storage.auth.*;
import peergos.shared.storage.controller.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.user.fs.cryptree.*;
import peergos.shared.util.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

/**
 *  This class is unprivileged - doesn't have any private keys
 */
public class NetworkAccess {
    private static final Logger LOG = Logger.getGlobal();

    public final Hasher hasher;
    public final CoreNode coreNode;
    public final Account account;
    public final SocialNetwork social;
    public final ContentAddressedStorage dhtClient;
    public final BatCave batCave;
    public final Optional<EncryptedBatCache> batCache;
    public final MutablePointers mutable;
    public final MutableTree tree;
    public final WriteSynchronizer synchronizer;
    public final InstanceAdmin instanceAdmin;
    public final SpaceUsage spaceUsage;
    public final ServerMessager serverMessager;

    @JsProperty
    public final List<String> usernames;
    public final CryptreeCache cache;
    private final LocalDateTime creationTime;
    private final boolean isJavascript;

    public NetworkAccess(CoreNode coreNode,
                         Account account,
                         SocialNetwork social,
                         ContentAddressedStorage dhtClient,
                         BatCave batCave,
                         Optional<EncryptedBatCache> batCache,
                         MutablePointers mutable,
                         MutableTree tree,
                         WriteSynchronizer synchronizer,
                         InstanceAdmin instanceAdmin,
                         SpaceUsage spaceUsage,
                         ServerMessager serverMessager,
                         Hasher hasher,
                         List<String> usernames,
                         CryptreeCache cache,
                         boolean isJavascript) {
        this.coreNode = coreNode;
        this.account = account;
        this.social = social;
        this.dhtClient = dhtClient;
        this.batCave = batCave;
        this.batCache = batCache;
        this.mutable = mutable;
        this.tree = tree;
        this.synchronizer = synchronizer;
        this.instanceAdmin = instanceAdmin;
        this.spaceUsage = spaceUsage;
        this.serverMessager = serverMessager;
        this.hasher = hasher;
        this.usernames = usernames;
        this.cache = cache;
        this.creationTime = LocalDateTime.now();
        this.isJavascript = isJavascript;
    }

    public NetworkAccess(CoreNode coreNode,
                         Account account,
                         SocialNetwork social,
                         ContentAddressedStorage dhtClient,
                         BatCave batCave,
                         Optional<EncryptedBatCache> batCache,
                         MutablePointers mutable,
                         MutableTree tree,
                         WriteSynchronizer synchronizer,
                         InstanceAdmin instanceAdmin,
                         SpaceUsage spaceUsage,
                         ServerMessager serverMessager,
                         Hasher hasher,
                         List<String> usernames,
                         boolean isJavascript) {
        this(coreNode, account, social, dhtClient, batCave, batCache, mutable, tree, synchronizer, instanceAdmin, spaceUsage, serverMessager,
                hasher, usernames, new CryptreeCache(), isJavascript);
    }

    public boolean isJavascript() {
    	return isJavascript;
    }

    public NetworkAccess withStorage(Function<ContentAddressedStorage, ContentAddressedStorage> modifiedStorage) {
        return new NetworkAccess(coreNode, account, social, modifiedStorage.apply(dhtClient), batCave, batCache, mutable, tree, synchronizer, instanceAdmin,
                spaceUsage, serverMessager, hasher, usernames, cache, isJavascript);
    }

    public NetworkAccess withMutablePointerOfflineCache(Function<MutablePointers, MutablePointers> modifiedPointers) {
        return new NetworkAccess(coreNode, account, social, dhtClient, batCave, batCache, modifiedPointers.apply(mutable),
                tree, synchronizer, instanceAdmin,
                spaceUsage, serverMessager, hasher, usernames, cache, isJavascript);
    }

    public NetworkAccess withBatOfflineCache(Optional<EncryptedBatCache> batCache) {
        return new NetworkAccess(coreNode, account, social, dhtClient, batCave, batCache, mutable,
                tree, synchronizer, instanceAdmin,
                spaceUsage, serverMessager, hasher, usernames, cache, isJavascript);
    }

    public NetworkAccess withAccountCache(Function<Account, Account> wrapper) {
        return new NetworkAccess(coreNode, wrapper.apply(account), social, dhtClient, batCave, batCache, mutable,
                tree, synchronizer, instanceAdmin,
                spaceUsage, serverMessager, hasher, usernames, cache, isJavascript);
    }

    public NetworkAccess withCorenode(CoreNode newCore) {
        return new NetworkAccess(newCore, account, social, dhtClient, batCave, batCache, mutable, tree, synchronizer, instanceAdmin,
                spaceUsage, serverMessager, hasher, usernames, cache, isJavascript);
    }

    public NetworkAccess withoutS3BlockStore() {
        ContentAddressedStorage directDht = dhtClient.directToOrigin();
        WriteSynchronizer synchronizer = new WriteSynchronizer(mutable, directDht, hasher);
        MutableTree tree = new MutableTreeImpl(mutable, directDht, hasher, synchronizer);
        return new NetworkAccess(coreNode, account, social, directDht, batCave, batCache, mutable, tree, synchronizer, instanceAdmin,
                spaceUsage, serverMessager, hasher, usernames, isJavascript);
    }

    public static BufferedNetworkAccess nonCommittingForSignup(Account account,
                                                               ContentAddressedStorage dht,
                                                               MutablePointers mutable,
                                                               BatCave bats,
                                                               Hasher hasher) {
        BufferedStorage blockBuffer = new BufferedStorage(dht, hasher);
        MutablePointers unbufferedMutable = mutable;
        BufferedPointers mutableBuffer = new BufferedPointers(unbufferedMutable);
        WriteSynchronizer synchronizer = new WriteSynchronizer(mutableBuffer, blockBuffer, hasher);
        MutableTree tree = new MutableTreeImpl(mutableBuffer, blockBuffer, hasher, synchronizer);

        int bufferSize = 1024 * 1024;
        return new BufferedNetworkAccess(blockBuffer, mutableBuffer, bufferSize, null, account,
                null, dht, unbufferedMutable, bats, Optional.empty(), tree, synchronizer, null,
                null, null, hasher, Collections.emptyList(), false);
    }

    @JsMethod
    public CompletableFuture<Optional<String>> otherDomain() {
        return dhtClient.blockStoreProperties()
                .thenApply(props -> props.baseAuthedUrl);
    }

    @JsMethod
    public CompletableFuture<Boolean> isUsernameRegistered(String username) {
        if (usernames.contains(username))
            return CompletableFuture.completedFuture(true);
        return coreNode.getChain(username).thenApply(chain -> chain.size() > 0);
    }

    public NetworkAccess clear() {
        MutablePointers mutable = this.mutable.clearCache();
        dhtClient.clearBlockCache();
        WriteSynchronizer synchronizer = new WriteSynchronizer(mutable, dhtClient, hasher);
        MutableTree mutableTree = new MutableTreeImpl(mutable, dhtClient, hasher, synchronizer);
        return new NetworkAccess(coreNode, account, social, dhtClient, batCave, batCache, mutable, mutableTree, synchronizer, instanceAdmin,
                spaceUsage, serverMessager, hasher, usernames, isJavascript);
    }

    public NetworkAccess withMutablePointerCache(int ttl) {
        CachingPointers mutable = new CachingPointers(this.mutable, ttl);
        WriteSynchronizer synchronizer = new WriteSynchronizer(mutable, dhtClient, hasher);
        MutableTree mutableTree = new MutableTreeImpl(mutable, dhtClient, hasher, synchronizer);
        return new NetworkAccess(coreNode, account, social, dhtClient, batCave, batCache, mutable, mutableTree, synchronizer, instanceAdmin,
                spaceUsage, serverMessager, hasher, usernames, isJavascript);
    }

    public static CoreNode buildProxyingCorenode(HttpPoster poster, Multihash pkiServerNodeId) {
        return new HTTPCoreNode(poster, pkiServerNodeId);
    }

    public static CoreNode buildDirectCorenode(HttpPoster poster) {
        return new HTTPCoreNode(poster);
    }

    public static ContentAddressedStorage buildLocalDht(HttpPoster apiPoster, boolean isPeergosServer, Hasher hasher) {
        return new ContentAddressedStorage.HTTP(apiPoster, isPeergosServer, hasher);
    }

    public static CompletableFuture<ContentAddressedStorage> buildDirectS3Blockstore(ContentAddressedStorage localDht,
                                                                                     CoreNode core,
                                                                                     HttpPoster direct,
                                                                                     boolean isPeergosServer,
                                                                                     Hasher hasher) {
        if (! isPeergosServer)
            return Futures.of(localDht);
        return localDht.blockStoreProperties()
                .exceptionally(t -> BlockStoreProperties.empty())
                .thenCompose(bp -> bp.useDirectBlockStore() ?
                        localDht.ids().thenApply(ids -> new DirectS3BlockStore(bp, direct, localDht, ids, core, hasher)) :
                        Futures.of(localDht));
    }

    @JsMethod
    public static CompletableFuture<NetworkAccess> buildJS(boolean isPublic,
                                                           int cacheSizeKiB,
                                                           boolean allowOfflineLogin) {
        JavaScriptPoster relative = new JavaScriptPoster(false, isPublic);
        ScryptJS hasher = new ScryptJS();
        boolean isPeergosServer = true; // we used to support using web ui through an ipfs gateway directly
        ContentAddressedStorage localDht = buildLocalDht(relative, isPeergosServer, hasher);
        OnlineState onlineState = new OnlineState(() -> localDht.id()
                .thenApply(x -> true)
                .exceptionally(t -> false));
        return buildViaPeergosInstance(relative, relative, localDht, 7_000, hasher, true)
                .thenApply(net -> net.withStorage(s ->
                        new UnauthedCachingStorage(s, new JSBlockCache(cacheSizeKiB/1024), hasher))
                        .withMutablePointerOfflineCache(m -> new OfflinePointerCache(m,
                                new JSPointerCache(2000, net.dhtClient),
                                onlineState)))
                .thenApply(net -> ! allowOfflineLogin ?
                        net :
                        net.withBatOfflineCache(Optional.of(new JSBatCache()))
                                .withAccountCache(a -> new OfflineAccountStore(net.account, new JSAccountCache(), onlineState))
                                .withCorenode(new OfflineCorenode(net.coreNode, new JSPkiCache(), onlineState)));
    }

    private static CompletableFuture<Boolean> isPeergosServer(HttpPoster poster) {
        CoreNode direct = buildDirectCorenode(poster);
        CompletableFuture<Boolean> res = new CompletableFuture<>();
        direct.getChain("peergos")
                .thenApply(x -> res.complete(true))
                .exceptionally(t -> res.complete(false));
        return res;
    }

    public static CompletableFuture<NetworkAccess> build(List<String> usernames,
                                                         CoreNode core,
                                                         HttpPoster apiPoster,
                                                         HttpPoster p2pPoster,
                                                         ContentAddressedStorage localDht,
                                                         int mutableCacheTime,
                                                         Hasher hasher,
                                                         boolean isJavascript) {
        return buildDirectS3Blockstore(localDht, core, apiPoster, true, hasher)
                .thenCompose(dht -> build(core, dht, apiPoster, p2pPoster, mutableCacheTime, hasher, usernames, true, isJavascript));
    }

    public static CompletableFuture<NetworkAccess> buildViaPeergosInstance(HttpPoster apiPoster,
                                                                            HttpPoster p2pPoster,
                                                                            ContentAddressedStorage localDht,
                                                                            int mutableCacheTime,
                                                                            Hasher hasher,
                                                                            boolean isJavascript) {
        CoreNode direct = buildDirectCorenode(apiPoster);
        return direct.getUsernames("")
                .exceptionally(t -> Collections.emptyList())
                .thenCompose(usernames -> build(usernames, direct, apiPoster, p2pPoster, localDht,
                        mutableCacheTime, hasher, isJavascript));
    }

    public static CompletableFuture<NetworkAccess> buildViaGateway(HttpPoster apiPoster,
                                                                   HttpPoster p2pPoster,
                                                                   Multihash pkiServerNodeId,
                                                                   int mutableCacheTime,
                                                                   Hasher hasher,
                                                                   boolean isJavascript) {
        // We are not on a Peergos server, hopefully an IPFS gateway
        ContentAddressedStorage localIpfs = buildLocalDht(apiPoster, false, hasher);
        CoreNode core = buildProxyingCorenode(p2pPoster, pkiServerNodeId);
        return core.getUsernames("").thenCompose(usernames ->
                        build(core, localIpfs, apiPoster, p2pPoster, mutableCacheTime, hasher,
                                usernames, false, isJavascript));
    }

    private static CompletableFuture<NetworkAccess> build(CoreNode core,
                                                          ContentAddressedStorage localDht,
                                                          HttpPoster apiPoster,
                                                          HttpPoster p2pPoster,
                                                          int mutableCacheTime,
                                                          Hasher hasher,
                                                          List<String> usernames,
                                                          boolean isPeergosServer,
                                                          boolean isJavascript) {
        return localDht.ids()
                .exceptionally(t -> Arrays.asList(new Cid(1, Cid.Codec.LibP2pKey, Multihash.Type.sha2_256, new byte[32])))
                .thenApply(nodeIds -> {
                    if (isPeergosServer)
                        return buildToPeergosServer(nodeIds, core, localDht, apiPoster, p2pPoster, mutableCacheTime, hasher, usernames, isJavascript);
                    ContentAddressedStorageProxy proxingDht = new ContentAddressedStorageProxy.HTTP(p2pPoster);
                    ContentAddressedStorage storage = new ContentAddressedStorage.Proxying(localDht, proxingDht, nodeIds, core);
                    ContentAddressedStorage p2pDht = new CachingVerifyingStorage(new RetryStorage(storage, 3),
                            100 * 1024, 1_000, nodeIds, hasher);
                    MutablePointersProxy httpMutable = new HttpMutablePointers(apiPoster, p2pPoster);
                    Account account = new HttpAccount(apiPoster, p2pPoster);
                    MutablePointers p2pMutable = new ProxyingMutablePointers(nodeIds, core, httpMutable, httpMutable);

                    SocialNetworkProxy httpSocial = new HttpSocialNetwork(apiPoster, p2pPoster);
                    SocialNetwork p2pSocial = new ProxyingSocialNetwork(nodeIds, core, httpSocial, httpSocial);
                    SpaceUsageProxy httpUsage = new HttpSpaceUsage(apiPoster, p2pPoster);
                    SpaceUsage p2pUsage = new ProxyingSpaceUsage(nodeIds, core, httpUsage, httpUsage);
                    ServerMessager serverMessager = new ServerMessager.HTTP(apiPoster);
                    BatCave batCave = new HttpBatCave(apiPoster, p2pPoster);
                    RetryMutablePointers retryMutable = new RetryMutablePointers(p2pMutable);
                    return build(p2pDht, batCave, core, account, retryMutable, mutableCacheTime, p2pSocial,
                            new HttpInstanceAdmin(apiPoster), p2pUsage, serverMessager, hasher, usernames, isJavascript);
                });
    }

    public static NetworkAccess buildToPeergosServer(List<Cid> nodeIds,
                                                     CoreNode core,
                                                     ContentAddressedStorage localDht,
                                                     HttpPoster apiPoster,
                                                     HttpPoster p2pPoster,
                                                     int mutableCacheTime,
                                                     Hasher hasher,
                                                     List<String> usernames,
                                                     boolean isJavascript) {
        ContentAddressedStorage p2pDht = new CachingVerifyingStorage(new RetryStorage(localDht, 3),
                100 * 1024, 1_000, nodeIds, hasher);
        MutablePointersProxy httpMutable = new HttpMutablePointers(apiPoster, p2pPoster);
        Account account = new HttpAccount(apiPoster, p2pPoster);

        SocialNetworkProxy httpSocial = new HttpSocialNetwork(apiPoster, p2pPoster);
        SpaceUsageProxy httpUsage = new HttpSpaceUsage(apiPoster, p2pPoster);
        ServerMessager serverMessager = new ServerMessager.HTTP(apiPoster);
        BatCave batCave = new HttpBatCave(apiPoster, p2pPoster);
        RetryMutablePointers retryMutable = new RetryMutablePointers(httpMutable);
        return build(p2pDht, batCave, core, account, retryMutable, mutableCacheTime, httpSocial,
                new HttpInstanceAdmin(apiPoster), httpUsage, serverMessager, hasher, usernames, isJavascript);
    }

    private static NetworkAccess build(ContentAddressedStorage dht,
                                       BatCave batCave,
                                       CoreNode coreNode,
                                       Account account,
                                       MutablePointers mutable,
                                       int mutableCacheTime,
                                       SocialNetwork social,
                                       InstanceAdmin instanceAdmin,
                                       SpaceUsage usage,
                                       ServerMessager serverMessager,
                                       Hasher hasher,
                                       List<String> usernames,
                                       boolean isJavascript) {
        BufferedStorage blockBuffer = new BufferedStorage(dht, hasher);
        MutablePointers unbufferedMutable = mutableCacheTime > 0 ? new CachingPointers(mutable, mutableCacheTime) : mutable;
        BufferedPointers mutableBuffer = new BufferedPointers(unbufferedMutable);
        WriteSynchronizer synchronizer = new WriteSynchronizer(mutableBuffer, blockBuffer, hasher);
        MutableTree tree = new MutableTreeImpl(mutableBuffer, blockBuffer, hasher, synchronizer);

        int bufferSize = 20 * 1024 * 1024;
        return new BufferedNetworkAccess(blockBuffer, mutableBuffer, bufferSize, coreNode, account,
                social, dht, unbufferedMutable, batCave, Optional.empty(), tree, synchronizer, instanceAdmin, usage, serverMessager, hasher, usernames, isJavascript);
    }

    public static CompletableFuture<NetworkAccess> buildPublicNetworkAccess(Hasher hasher,
                                                                            CoreNode core,
                                                                            MutablePointers mutable,
                                                                            ContentAddressedStorage storage) {
        WriteSynchronizer synchronizer = new WriteSynchronizer(mutable, storage, hasher);
        MutableTree mutableTree = new MutableTreeImpl(mutable, storage, null, synchronizer);
        return CompletableFuture.completedFuture(new NetworkAccess(core, null, null, storage, null, Optional.empty(), mutable, mutableTree,
                synchronizer, null, null, null, hasher, Collections.emptyList(), false));
    }

    public boolean isFull() {
        return false;
    }

    public NetworkAccess disableCommits() {
        return this;
    }

    public NetworkAccess enableCommits() {
        return this;
    }

    public Committer buildCommitter(Committer c, PublicKeyHash owner, Supplier<Boolean> commitWatcher) {
        return c;
    }

    public CompletableFuture<Boolean> commit(PublicKeyHash owner) {
        return commit(owner, () -> true);
    }

    public CompletableFuture<Boolean> commit(PublicKeyHash owner, Supplier<Boolean> commitWatcher) {
        return Futures.of(true);
    }

    public CompletableFuture<Optional<RetrievedCapability>> retrieveMetadata(AbsoluteCapability cap, Snapshot version) {
        return retrieveAllMetadata(Collections.singletonList(cap), version)
                .thenApply(res -> res.isEmpty() ? Optional.empty() : Optional.of(res.get(0)));
    }

    public CompletableFuture<List<RetrievedCapability>> retrieveAllMetadata(List<AbsoluteCapability> links, Snapshot current) {
        List<CompletableFuture<Optional<RetrievedCapability>>> all = links.stream()
                .map(link -> current.withWriter(link.owner, link.writer, this)
                        .thenCompose(version -> getMetadata(version.get(link.writer).props, link)
                                .thenApply(copt -> copt.map(c -> new RetrievedCapability(link, c)))))
                .collect(Collectors.toList());

        return Futures.combineAll(all)
                .thenApply(optSet -> optSet.stream()
                        .flatMap(Optional::stream)
                        .collect(Collectors.toList()));
    }

    public CompletableFuture<Set<FileWrapper>> retrieveAll(List<EntryPoint> entries) {
        return Futures.reduceAll(entries, Collections.emptySet(),
                (set, entry) -> retrieveEntryPoint(entry)
                        .thenApply(opt ->
                                opt.map(f -> Stream.concat(set.stream(), Stream.of(f)).collect(Collectors.toSet()))
                                        .orElse(set)),
                (a, b) -> Stream.concat(a.stream(), b.stream()).collect(Collectors.toSet()));
    }

    public CompletableFuture<Optional<FileWrapper>> retrieveEntryPoint(EntryPoint e) {
        return synchronizer.getValue(e.pointer.owner, e.pointer.writer)
                .thenCompose(version -> getFile(version, e.pointer, Optional.empty(), e.ownerName))
                .exceptionally(t -> {
                    LOG.log(Level.SEVERE, t.getMessage(), t);
                    return Optional.empty();
                });
    }

    public static CompletableFuture<RetrievedEntryPoint> retrieveEntryPoint(EntryPoint e, NetworkAccess network) {
        return network.retrieveEntryPoint(e)
                .thenCompose(fileOpt -> {
                    if (! fileOpt.isPresent())
                        throw new IllegalStateException("Couldn't retrieve entry point");
                    return fileOpt.get().getPath(network)
                            .thenApply(path -> new RetrievedEntryPoint(e, path, fileOpt.get()));
                });
    }

    public static CompletableFuture<RetrievedEntryPoint> getLatestEntryPoint(EntryPoint e, NetworkAccess network) {
        return Futures.asyncExceptionally(() -> retrieveEntryPoint(e, network),
                ex -> getUptodateEntryPoint(e, network)
                        .thenCompose(updated -> retrieveEntryPoint(updated, network)));
    }

    private static CompletableFuture<EntryPoint> getUptodateEntryPoint(EntryPoint e, NetworkAccess network) {
        // User might have changed their identity key, check for an update
        return network.coreNode.updateUser(e.ownerName)
                .thenCompose(x -> network.coreNode.getPublicKeyHash(e.ownerName))
                .thenApply(currentIdOpt -> {
                    if (!currentIdOpt.isPresent() || currentIdOpt.get().equals(e.pointer.owner))
                        throw new IllegalStateException("Couldn't retrieve entry point for user " + e.ownerName);
                    return new EntryPoint(e.pointer.withOwner(currentIdOpt.get()), e.ownerName);
                });
    }

    @JsMethod
    public CompletableFuture<Optional<FileWrapper>> getFile(AbsoluteCapability cap, String owner) {
        return synchronizer.getValue(cap.owner, cap.writer)
                .thenCompose(version -> getFile(version, cap, Optional.empty(), owner))
                .exceptionally(t -> {
                    LOG.log(Level.SEVERE, t.getMessage(), t);
                    return Optional.empty();
                });
    }

    public CompletableFuture<Optional<FileWrapper>> getFile(EntryPoint e, Snapshot version) {
        if (version.contains(e.pointer.writer))
            return getFile(version, e.pointer, Optional.empty(), e.ownerName);
        return retrieveEntryPoint(e);
    }

    public CompletableFuture<Optional<FileWrapper>> getFile(Snapshot version,
                                                            AbsoluteCapability cap,
                                                            Optional<SigningPrivateKeyAndPublicHash> entryWriter,
                                                            String ownerName) {
        return version.withWriter(cap.owner, cap.writer, this)
                .thenCompose(v -> getMetadata(v.get(cap.writer).props, cap)
                .thenCompose(faOpt -> {
                    if (! faOpt.isPresent())
                        return Futures.of(Optional.empty());
                    CryptreeNode fa = faOpt.get();
                    RetrievedCapability rc = new RetrievedCapability(cap, fa);
                    try {
                        FileProperties props = rc.getProperties();
                        if (!props.isLink)
                            return Futures.of(Optional.of(new FileWrapper(Optional.empty(),
                                    rc,
                                    Optional.empty(),
                                    cap.wBaseKey.map(wBase -> fa.getSigner(cap.rBaseKey, wBase, entryWriter)), ownerName, v)));
                        return getFileFromLink(cap.owner, rc, entryWriter, ownerName, this, v)
                                .thenApply(f -> Optional.of(f));
                    } catch (InvalidCipherTextException e) {
                        LOG.info("Couldn't decrypt file from friend: " + ownerName);
                        return Futures.of(Optional.empty());
                    }
                }));
    }

    public static CompletableFuture<FileWrapper> getFileFromLink(PublicKeyHash owner,
                                                                 RetrievedCapability link,
                                                                 Optional<SigningPrivateKeyAndPublicHash> entryWriter,
                                                                 String ownername,
                                                                 NetworkAccess network,
                                                                 Snapshot version) {
        // the link is formatted as a directory cryptree node and the target is the solitary child
        return link.fileAccess.getDirectChildrenCapabilities(link.capability, version, network)
                .thenCompose(children -> {
                    if (children.size() != 1)
                        throw new IllegalStateException("Link cryptree nodes must have exactly one child link!");
                    AbsoluteCapability cap = children.stream().findFirst().get().cap;
                    Set<PublicKeyHash> childWriters = Collections.singleton(cap.writer);
                    return version.withWriters(owner, childWriters, network)
                            .thenCompose(fullVersion -> network.retrieveAllMetadata(Collections.singletonList(cap), fullVersion)
                                    .thenCompose(rcs -> {
                                        RetrievedCapability rc = rcs.get(0);
                                        FileProperties props = rc.getProperties();
                                        if (!props.isLink)
                                            return Futures.of(new FileWrapper(rc, Optional.of(link), entryWriter, ownername, fullVersion));
                                        // We have a link chain! Be sure to use the name from the first link,
                                        // and remaining properties from the final target
                                        return getFileFromLink(owner, rc, entryWriter, ownername, network, fullVersion)
                                                .thenApply(f -> new FileWrapper(f.getPointer(), Optional.of(link), entryWriter, ownername, fullVersion));
                                    }));
                });
    }

    public CompletableFuture<Optional<CryptreeNode>> getMetadata(WriterData base, AbsoluteCapability cap) {
        return getMetadata(base, cap, Optional.empty());
    }

    public CompletableFuture<Optional<CryptreeNode>> getMetadata(WriterData base, AbsoluteCapability cap, Optional<Cid> committedRoot) {
        if (base.tree.isEmpty())
            return Futures.of(Optional.empty());
        Pair<Multihash, ByteArrayWrapper> cacheKey = new Pair<>(base.tree.get(), new ByteArrayWrapper(cap.getMapKey()));
        if (cache.containsKey(cacheKey))
            return Futures.of(cache.get(cacheKey));
        return cap.bat.map(b -> b.calculateId(hasher).thenApply(id -> Optional.of(new BatWithId(b, id.id)))).orElse(Futures.of(Optional.empty()))
                .thenCompose(bat -> Futures.asyncExceptionally(
                        () -> dhtClient.getChampLookup(cap.owner, (Cid) base.tree.get(), cap.getMapKey(), bat,committedRoot),
                        t -> dhtClient.getChampLookup(cap.owner, (Cid) base.tree.get(), cap.getMapKey(), bat, committedRoot, hasher)
                ).thenCompose(blocks -> ChampWrapper.create(cap.owner, (Cid)base.tree.get(), x -> Futures.of(x.data), dhtClient, hasher, c -> (CborObject.CborMerkleLink) c)
                        .thenCompose(tree -> tree.get(cap.getMapKey()))
                        .thenApply(c -> c.map(x -> x.target))
                        .thenCompose(btreeValue -> {
                            if (btreeValue.isPresent()) {
                                return dhtClient.get(cap.owner, (Cid) btreeValue.get(), bat)
                                        .thenApply(value -> value.map(cbor -> CryptreeNode.fromCbor(cbor, cap.rBaseKey, btreeValue.get())))
                                        .thenApply(res -> {
                                            cache.put(cacheKey, res);
                                            return res;
                                        });
                            }
                            return CompletableFuture.completedFuture(Optional.empty());
                        })));
    }

    private CompletableFuture<List<Cid>> bulkUploadFragments(List<Fragment> fragments,
                                                             PublicKeyHash owner,
                                                             PublicKeyHash writer,
                                                             List<byte[]> signatures,
                                                             TransactionId tid,
                                                             ProgressConsumer<Long> progressCounter) {
        return dhtClient.putRaw(owner, writer, signatures, fragments
                .stream()
                .map(f -> f.data)
                .collect(Collectors.toList()), tid, progressCounter);
    }

    public CompletableFuture<List<Cid>> uploadFragments(List<Fragment> fragments,
                                                        PublicKeyHash owner,
                                                        SigningPrivateKeyAndPublicHash writer,
                                                        ProgressConsumer<Long> progressCounter,
                                                        TransactionId tid) {
        if (fragments.isEmpty())
            return CompletableFuture.completedFuture(Collections.emptyList());

        return Futures.combineAllInOrder(fragments.stream()
                .map(f -> hasher.sha256(f.data))
                .collect(Collectors.toList()))
                .thenCompose(hashes -> bulkUploadFragments(
                        fragments,
                        owner,
                        writer.publicKeyHash,
                        hashes.stream()
                                .map(writer.secret::signMessage)
                                .collect(Collectors.toList()),
                        tid,
                        progressCounter
                ));
    }

    public CompletableFuture<Snapshot> uploadChunk(Snapshot current,
                                                   Committer committer,
                                                   CryptreeNode metadata,
                                                   PublicKeyHash owner,
                                                   byte[] mapKey,
                                                   SigningPrivateKeyAndPublicHash writer,
                                                   TransactionId tid) {
        if (! current.versions.containsKey(writer.publicKeyHash))
            throw new IllegalStateException("Trying to commit to incorrect writer!");
        try {
            LOG.info("Uploading chunk: " + (metadata.isDirectory() ? "dir" : "file")
                    + " at " + ArrayOps.bytesToHex(mapKey)
                    + " with " + metadata.toCbor().links().size() + " fragments");
            byte[] metaBlob = metadata.serialize();
            CommittedWriterData version = current.get(writer);
            return hasher.sha256(metaBlob)
                    .thenCompose(blobSha -> dhtClient.put(owner, writer.publicKeyHash,
                            writer.secret.signMessage(blobSha), metaBlob, tid))
                    .thenCompose(blobHash -> tree.put(version.props, owner, writer, mapKey,
                            metadata.committedHash(), blobHash, tid)
                            .thenCompose(wd -> committer.commit(owner, writer, wd, version, tid)
                                    .thenApply(s -> {
                                        cache.update(version.props.tree, new Pair<>(wd.tree.get(), new ByteArrayWrapper(mapKey)), Optional.of(metadata.withHash(blobHash)));
                                        return s;
                                    })))
                    .thenApply(committed -> current.withVersion(writer.publicKeyHash, committed.get(writer)));
        } catch (Exception e) {
            LOG.severe(e.getMessage());
            throw new RuntimeException(e);
        }
    }

    public CompletableFuture<Snapshot> addPreexistingChunk(CryptreeNode metadata,
                                                           PublicKeyHash owner,
                                                           byte[] mapKey,
                                                           SigningPrivateKeyAndPublicHash writer,
                                                           TransactionId tid,
                                                           Snapshot current,
                                                           Committer committer) {
        CommittedWriterData version = current.get(writer);
        return tree.put(version.props, owner, writer, mapKey, metadata.committedHash(), metadata.committedHash().get(), tid)
                .thenCompose(wd -> committer.commit(owner, writer, wd, version, tid));
    }

    public CompletableFuture<Snapshot> deleteChunk(Snapshot current,
                                                   Committer committer,
                                                   CryptreeNode metadata,
                                                   PublicKeyHash owner,
                                                   byte[] mapKey,
                                                   SigningPrivateKeyAndPublicHash writer,
                                                   TransactionId tid) {
        CommittedWriterData version = current.get(writer);
        return tree.remove(version.props, owner, writer, mapKey, metadata.committedHash(), tid)
                .thenCompose(wd -> committer.commit(owner, writer, wd, version, tid))
                .thenApply(committed -> current.withVersion(writer.publicKeyHash, committed.get(writer)));
    }

    public CompletableFuture<Snapshot> deleteChunkIfPresent(Snapshot current,
                                                            Committer committer,
                                                            PublicKeyHash owner,
                                                            SigningPrivateKeyAndPublicHash writer,
                                                            byte[] mapKey,
                                                            TransactionId tid) {
        CommittedWriterData version = current.get(writer);
        return tree.get(version.props, owner, writer.publicKeyHash, mapKey)
                .thenCompose(valueHash ->
                        ! valueHash.isPresent() ? CompletableFuture.completedFuture(current) :
                                tree.remove(version.props, owner, writer, mapKey, valueHash, tid)
                                        .thenCompose(wd -> committer.commit(owner, writer, wd, version, tid)))
                .thenApply(committed -> current.withVersion(writer.publicKeyHash, committed.get(writer)));
    }

    public CompletableFuture<Snapshot> deleteAllChunksIfPresent(Snapshot current,
                                                                Committer committer,
                                                                PublicKeyHash owner,
                                                                SigningPrivateKeyAndPublicHash writer,
                                                                List<byte[]> mapKeys,
                                                                TransactionId tid) {
        CommittedWriterData version = current.get(writer);
        List<CompletableFuture<Pair<byte[], MaybeMultihash>>> withValues = mapKeys.stream()
                .parallel()
                .map(mapKey -> tree.get(version.props, owner, writer.publicKeyHash, mapKey)
                        .thenApply(valueHash -> new Pair<>(mapKey, valueHash)))
                .collect(Collectors.toList());
        return Futures.reduceAll(withValues, version.props,
                        (wd, f) -> f.thenCompose(p -> p.right.isPresent() ?
                                tree.remove(wd, owner, writer, p.left, p.right, tid) :
                                Futures.of(wd)),
                        (a, b) -> b)
                .thenCompose(wd -> committer.commit(owner, writer, wd, version, tid))
                .thenApply(committed -> current.withVersion(writer.publicKeyHash, committed.get(writer)));
    }

    public CompletableFuture<Boolean> chunkIsPresent(Snapshot current,
                                                     PublicKeyHash owner,
                                                     PublicKeyHash writer,
                                                     byte[] mapKey) {
        CommittedWriterData version = current.get(writer);
        return tree.get(version.props, owner, writer, mapKey)
                .thenApply(valueHash -> valueHash.isPresent());
    }

    public static CompletableFuture<List<FragmentWithHash>> downloadFragments(PublicKeyHash owner,
                                                                              List<Cid> hashes,
                                                                              List<BatWithId> bats,
                                                                              ContentAddressedStorage dhtClient,
                                                                              Hasher hasher,
                                                                              ProgressConsumer<Long> monitor,
                                                                              double spaceIncreaseFactor) {
        List<CompletableFuture<Optional<FragmentWithHash>>> futures = IntStream.range(0, hashes.size()).mapToObj(i -> i)
                .parallel()
                .map(i -> {
                    Cid h = hashes.get(i);
                    return (h.isIdentity() ?
                            CompletableFuture.completedFuture(Optional.of(h.getHash())) :
                            h.codec == Cid.Codec.Raw ?
                                    dhtClient.getRaw(owner, h, i < bats.size() ? Optional.of(bats.get(i)) : Optional.empty()) :
                                    dhtClient.get(owner, h, i < bats.size() ? Optional.of(bats.get(i)) : Optional.empty())
                                            .thenApply(cborOpt -> cborOpt.map(cbor -> ((CborObject.CborByteArray) cbor).value))) // for backwards compatibility
                            .thenApply(dataOpt -> {
                                Optional<byte[]> bytes = dataOpt;
                                bytes.ifPresent(arr -> monitor.accept((long) (arr.length / spaceIncreaseFactor)));
                                return bytes.map(data -> new FragmentWithHash(new Fragment(data), h.isIdentity() ? Optional.empty() : Optional.of(h)));
                            });
                }).collect(Collectors.toList());

        return Futures.combineAllInOrder(futures)
                .thenApply(optList -> optList.stream().filter(Optional::isPresent).map(Optional::get).collect(Collectors.toList()));
    }
}
