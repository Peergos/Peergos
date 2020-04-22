package peergos.shared;
import java.util.logging.*;

import jsinterop.annotations.*;
import peergos.client.*;
import peergos.shared.cbor.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.mutable.*;
import peergos.shared.social.*;
import peergos.shared.storage.*;
import peergos.shared.storage.controller.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.user.fs.cryptree.*;
import peergos.shared.util.*;

import java.net.*;
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
    public final SocialNetwork social;
    public final ContentAddressedStorage dhtClient;
    public final MutablePointers mutable;
    public final MutableTree tree;
    public final WriteSynchronizer synchronizer;
    public final InstanceAdmin instanceAdmin;
    public final SpaceUsage spaceUsage;

    @JsProperty
    public final List<String> usernames;
    private final LocalDateTime creationTime;
    private final boolean isJavascript;

    public NetworkAccess(CoreNode coreNode,
                            SocialNetwork social,
                            ContentAddressedStorage dhtClient,
                            MutablePointers mutable,
                            MutableTree tree,
                            WriteSynchronizer synchronizer,
                            InstanceAdmin instanceAdmin,
                            SpaceUsage spaceUsage,
                            List<String> usernames,
                            boolean isJavascript) {
        this.coreNode = coreNode;
        this.social = social;
        this.dhtClient = dhtClient;
        this.mutable = mutable;
        this.tree = tree;
        this.synchronizer = synchronizer;
        this.instanceAdmin = instanceAdmin;
        this.spaceUsage = spaceUsage;
        this.hasher = isJavascript ? new ScryptJS() : new ScryptJava();
        this.usernames = usernames;
        this.creationTime = LocalDateTime.now();
        this.isJavascript = isJavascript;
    }

    public boolean isJavascript() {
    	return isJavascript;
    }

    public NetworkAccess withCorenode(CoreNode newCore) {
        return new NetworkAccess(newCore, social, dhtClient, mutable, tree, synchronizer, instanceAdmin, spaceUsage, usernames, isJavascript);
    }

    @JsMethod
    public CompletableFuture<Boolean> isUsernameRegistered(String username) {
        if (usernames.contains(username))
            return CompletableFuture.completedFuture(true);
        return coreNode.getChain(username).thenApply(chain -> chain.size() > 0);
    }

    public NetworkAccess clear() {
        WriteSynchronizer synchronizer = new WriteSynchronizer(mutable, dhtClient, hasher);
        MutableTree mutableTree = new MutableTreeImpl(mutable, dhtClient, hasher, synchronizer);
        return new NetworkAccess(coreNode, social, dhtClient, mutable, mutableTree, synchronizer, instanceAdmin,
                spaceUsage, usernames, isJavascript);
    }

    public NetworkAccess withMutablePointerCache(int ttl) {
        CachingPointers mutable = new CachingPointers(this.mutable, ttl);
        WriteSynchronizer synchronizer = new WriteSynchronizer(mutable, dhtClient, hasher);
        MutableTree mutableTree = new MutableTreeImpl(mutable, dhtClient, hasher, synchronizer);
        return new NetworkAccess(coreNode, social, dhtClient, mutable, mutableTree, synchronizer, instanceAdmin,
                spaceUsage, usernames, isJavascript);
    }

    public static CoreNode buildProxyingCorenode(HttpPoster poster, Multihash pkiServerNodeId) {
        return new HTTPCoreNode(poster, pkiServerNodeId);
    }

    public static CoreNode buildDirectCorenode(HttpPoster poster) {
        return new HTTPCoreNode(poster);
    }

    public static ContentAddressedStorage buildLocalDht(HttpPoster apiPoster, boolean isPeergosServer) {
        return new ContentAddressedStorage.HTTP(apiPoster, isPeergosServer);
    }

    public static CompletableFuture<ContentAddressedStorage> buildDirectS3Blockstore(ContentAddressedStorage localDht,
                                                                                     HttpPoster direct,
                                                                                     boolean isPeergosServer) {
        if (! isPeergosServer)
            return Futures.of(localDht);
        return localDht.blockStoreProperties()
                .thenApply(bp -> bp.baseUrl.isPresent() ?
                        new DirectReadS3BlockStore(bp.baseUrl.get(), direct, localDht) :
                        localDht);
    }

    @JsMethod
    public static CompletableFuture<NetworkAccess> buildJS(String pkiNodeId, boolean isPublic) {
        Multihash pkiServerNodeId = Cid.decode(pkiNodeId);
        System.setOut(new ConsolePrintStream());
        System.setErr(new ConsolePrintStream());
        JavaScriptPoster relative = new JavaScriptPoster(false, isPublic);
        JavaScriptPoster absolute = new JavaScriptPoster(true, true);

        return isPeergosServer(relative)
                .thenApply(isPeergosServer -> new Pair<>(isPeergosServer ? relative : absolute, isPeergosServer))
                .thenCompose(p ->
                        buildDirectS3Blockstore(buildLocalDht(p.left, true), relative, p.right)
                                .thenCompose(dht -> build(p.left, p.left, pkiServerNodeId, dht, new ScryptJS(), true)))
                .thenApply(e -> e.withMutablePointerCache(7_000));
    }

    private static CompletableFuture<Boolean> isPeergosServer(HttpPoster poster) {
        CoreNode direct = buildDirectCorenode(poster);
        CompletableFuture<Boolean> res = new CompletableFuture<>();
        direct.getChain("peergos")
                .thenApply(x -> res.complete(true))
                .exceptionally(t -> res.complete(false));
        return res;
    }

    public static CompletableFuture<NetworkAccess> buildJava(URL apiAddress, URL proxyAddress, String pkiNodeId) {
        Multihash pkiServerNodeId = Cid.decode(pkiNodeId);
        JavaPoster p2pPoster = new JavaPoster(proxyAddress);
        JavaPoster apiPoster = new JavaPoster(apiAddress);
        return build(apiPoster, p2pPoster, pkiServerNodeId, buildLocalDht(apiPoster, true), new ScryptJava(), false);
    }

    public static CompletableFuture<NetworkAccess> build(HttpPoster apiPoster,
                                                         HttpPoster p2pPoster,
                                                         Multihash pkiServerNodeId,
                                                         ContentAddressedStorage localDht,
                                                         Hasher hasher,
                                                         boolean isJavascript) {
        CoreNode direct = buildDirectCorenode(apiPoster);
        CompletableFuture<NetworkAccess> result = new CompletableFuture<>();
        direct.getUsernames("")
                .thenAccept(usernames -> {
                    // We are on a Peergos server
                    CoreNode core = direct;
                    build(core, localDht, apiPoster, p2pPoster, usernames, true, isJavascript)
                            .thenApply(result::complete)
                            .exceptionally(t -> {
                                result.completeExceptionally(t);
                                return true;
                            });
                })
                .exceptionally(t -> {
                    // We are not on a Peergos server, hopefully an IPFS gateway
                    ContentAddressedStorage localIpfs = buildLocalDht(apiPoster, false);
                    CoreNode core = buildProxyingCorenode(p2pPoster, pkiServerNodeId);
                    core.getUsernames("").thenCompose(usernames ->
                            build(core, localIpfs, apiPoster, p2pPoster, usernames, false, isJavascript)
                                    .thenApply(result::complete))
                            .exceptionally(t2 -> {
                                result.completeExceptionally(t2);
                                return true;
                            });
                    return null;
                });
        return result;
    }

    private static CompletableFuture<NetworkAccess> build(CoreNode core,
                                                          ContentAddressedStorage localDht,
                                                          HttpPoster apiPoster,
                                                          HttpPoster p2pPoster,
                                                          List<String> usernames,
                                                          boolean isPeergosServer,
                                                          boolean isJavascript) {
        return localDht.id()
                .exceptionally(t -> new Multihash(Multihash.Type.sha2_256, new byte[32]))
                .thenApply(nodeId -> {
                    ContentAddressedStorageProxy proxingDht = new ContentAddressedStorageProxy.HTTP(p2pPoster);
                    ContentAddressedStorage storage = isPeergosServer ?
                            localDht :
                            new ContentAddressedStorage.Proxying(localDht, proxingDht, nodeId, core);
                    HashVerifyingStorage verifyingStorage = new HashVerifyingStorage(storage, isJavascript ? new ScryptJS() : new ScryptJava());
                    ContentAddressedStorage p2pDht = new CachingStorage(verifyingStorage, 1_000, 50 * 1024);
                    MutablePointersProxy httpMutable = new HttpMutablePointers(apiPoster, p2pPoster);
                    MutablePointers p2pMutable =
                            isPeergosServer ?
                                    httpMutable :
                                    new ProxyingMutablePointers(nodeId, core, httpMutable, httpMutable);

                    SocialNetworkProxy httpSocial = new HttpSocialNetwork(apiPoster, p2pPoster);
                    SocialNetwork p2pSocial = isPeergosServer ?
                            httpSocial :
                            new ProxyingSocialNetwork(nodeId, core, httpSocial, httpSocial);
                    SpaceUsageProxy httpUsage = new HttpSpaceUsage(apiPoster, p2pPoster);
                    SpaceUsage p2pUsage = isPeergosServer ?
                            httpUsage :
                            new ProxyingSpaceUsage(nodeId, core, httpUsage, httpUsage);
                    return build(p2pDht, core, p2pMutable, p2pSocial, new HttpInstanceAdmin(apiPoster), p2pUsage, usernames, isJavascript);
                });
    }

    private static NetworkAccess build(ContentAddressedStorage dht,
                                       CoreNode coreNode,
                                       MutablePointers mutable,
                                       SocialNetwork social,
                                       InstanceAdmin instanceAdmin,
                                       SpaceUsage usage,
                                       List<String> usernames,
                                       boolean isJavascript) {
        Hasher hasher = isJavascript ? new ScryptJS() : new ScryptJava();
        WriteSynchronizer synchronizer = new WriteSynchronizer(mutable, dht, hasher);
        MutableTree btree = new MutableTreeImpl(mutable, dht, hasher, synchronizer);
        return new NetworkAccess(coreNode, social, dht, mutable, btree, synchronizer, instanceAdmin, usage, usernames, isJavascript);
    }

    public static CompletableFuture<NetworkAccess> buildJava(URL target) {
        return buildNonCachingJava(target)
                .thenApply(e -> e.withMutablePointerCache(7_000));
    }

    public static CompletableFuture<NetworkAccess> buildNonCachingJava(URL target) {
        JavaPoster poster = new JavaPoster(target);
        CoreNode direct = buildDirectCorenode(poster);
        try {
            List<String> usernames = direct.getUsernames("").get();
            boolean isPeergosServer = true;
            ContentAddressedStorage localDht = buildLocalDht(poster, isPeergosServer);
            return build(direct, localDht, poster, poster, usernames, isPeergosServer, false);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static CompletableFuture<NetworkAccess> buildJava(int targetPort) {
        try {
            return buildJava(new URL("http://localhost:" + targetPort + "/"));
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }

    public static CompletableFuture<NetworkAccess> buildPublicNetworkAccess(CoreNode core,
                                                                            MutablePointers mutable,
                                                                            ContentAddressedStorage storage) {
        Hasher hasher = null; // not needed for read only public access
        WriteSynchronizer synchronizer = new WriteSynchronizer(mutable, storage, hasher);
        MutableTree mutableTree = new MutableTreeImpl(mutable, storage, null, synchronizer);
        return CompletableFuture.completedFuture(new NetworkAccess(core, null, storage, mutable, mutableTree,
                synchronizer, null, null, Collections.emptyList(), false));
    }

    public CompletableFuture<Optional<RetrievedCapability>> retrieveMetadata(AbsoluteCapability cap, Snapshot version) {
        return retrieveAllMetadata(Collections.singletonList(cap), version)
                .thenApply(res -> res.isEmpty() ? Optional.empty() : Optional.of(res.get(0)));
    }

    public CompletableFuture<List<RetrievedCapability>> retrieveAllMetadata(List<AbsoluteCapability> links, Snapshot current) {
        List<CompletableFuture<Optional<RetrievedCapability>>> all = links.stream()
                .map(link -> {
                    PublicKeyHash owner = link.owner;
                    PublicKeyHash writer = link.writer;
                    byte[] mapKey = link.getMapKey();
                    return current.withWriter(owner, writer, this).thenCompose(version ->
                            tree.get(version.get(writer).props, owner, writer, mapKey))
                            .thenCompose(key -> {
                                if (key.isPresent())
                                    return dhtClient.get(key.get())
                                            .thenApply(dataOpt ->  dataOpt
                                                    .map(cbor -> new RetrievedCapability(
                                                            link,
                                                            CryptreeNode.fromCbor(cbor, link.rBaseKey, key.get()))));
                                LOG.severe("Couldn't download link at: " + new Location(owner, writer, mapKey));
                                Optional<RetrievedCapability> result = Optional.empty();
                                return CompletableFuture.completedFuture(result);
                            });
                }).collect(Collectors.toList());

        return Futures.combineAll(all).thenApply(optSet -> optSet.stream()
                .filter(Optional::isPresent)
                .map(Optional::get)
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
        // User might have changed their password and thus identity key, check for an update
        return network.coreNode.updateUser(e.ownerName)
                .thenCompose(x -> network.coreNode.getPublicKeyHash(e.ownerName))
                .thenApply(currentIdOpt -> {
                    if (!currentIdOpt.isPresent() || currentIdOpt.get().equals(e.pointer.owner))
                        throw new IllegalStateException("Couldn't retrieve entry point for user " + e.ownerName);
                    return new EntryPoint(e.pointer.withOwner(currentIdOpt.get()), e.ownerName);
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
        return getMetadata(version.get(cap.writer).props, cap)
                .thenCompose(faOpt -> {
                    if (! faOpt.isPresent())
                        return Futures.of(Optional.empty());
                    CryptreeNode fa = faOpt.get();
                    RetrievedCapability rc = new RetrievedCapability(cap, fa);
                    FileProperties props = rc.getProperties();
                    if (! props.isLink)
                        return Futures.of(Optional.of(new FileWrapper(Optional.empty(),
                                rc,
                                Optional.empty(),
                                cap.wBaseKey.map(wBase -> fa.getSigner(cap.rBaseKey, wBase, entryWriter)), ownerName, version)));
                    return getFileFromLink(cap.owner, rc, entryWriter, ownerName, this, version)
                            .thenApply(f -> Optional.of(f));
                });
    }

    public static CompletableFuture<FileWrapper> getFileFromLink(PublicKeyHash owner,
                                                                 RetrievedCapability link,
                                                                 Optional<SigningPrivateKeyAndPublicHash> entryWriter,
                                                                 String ownername,
                                                                 NetworkAccess network,
                                                                 Snapshot version) {
        // the link is formatted as a directory cryptree node and the target is the solitary child
        return link.fileAccess.getDirectChildrenCapabilities(link.capability, network)
                .thenCompose(children -> {
                    if (children.size() != 1)
                        throw new IllegalStateException("Link cryptree nodes must have exactly one child link!");
                    AbsoluteCapability cap = children.stream().findFirst().get();
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
                                        return getFileFromLink(owner, rc, entryWriter, ownername, network, version)
                                                .thenApply(f -> new FileWrapper(f.getPointer(), Optional.of(link), entryWriter, ownername, fullVersion));
                                    }));
                });
    }

    public CompletableFuture<Optional<CryptreeNode>> getMetadata(WriterData base, AbsoluteCapability cap) {
        return tree.get(base, cap.owner, cap.writer, cap.getMapKey()).thenCompose(btreeValue -> {
            if (btreeValue.isPresent())
                return dhtClient.get(btreeValue.get())
                        .thenApply(value -> value.map(cbor -> CryptreeNode.fromCbor(cbor,  cap.rBaseKey, btreeValue.get())));
            return CompletableFuture.completedFuture(Optional.empty());
        });
    }

    private CompletableFuture<Multihash> uploadFragment(Fragment f,
                                                        PublicKeyHash owner,
                                                        PublicKeyHash writer,
                                                        byte[] signature,
                                                        TransactionId tid) {
        return dhtClient.putRaw(owner, writer, signature, f.data, tid);
    }

    private CompletableFuture<List<Multihash>> bulkUploadFragments(List<Fragment> fragments,
                                                                   PublicKeyHash owner,
                                                                   PublicKeyHash writer,
                                                                   List<byte[]> signatures,
                                                                   TransactionId tid) {
        return dhtClient.putRaw(owner, writer, signatures, fragments
                .stream()
                .map(f -> f.data)
                .collect(Collectors.toList()), tid);
    }

    public CompletableFuture<List<Multihash>> uploadFragments(List<Fragment> fragments,
                                                              PublicKeyHash owner,
                                                              SigningPrivateKeyAndPublicHash writer,
                                                              ProgressConsumer<Long> progressCounter,
                                                              TransactionId tid) {
        if (fragments.isEmpty())
            return CompletableFuture.completedFuture(Collections.emptyList());
        // upload one per query because IPFS doesn't support more than one
        int FRAGMENTs_PER_QUERY = 1;
        List<List<Fragment>> grouped = IntStream.range(0, (fragments.size() + FRAGMENTs_PER_QUERY - 1) / FRAGMENTs_PER_QUERY)
                .mapToObj(i -> fragments.stream().skip(FRAGMENTs_PER_QUERY * i).limit(FRAGMENTs_PER_QUERY).collect(Collectors.toList()))
                .collect(Collectors.toList());
        List<Integer> sizes = grouped.stream()
                .map(frags -> frags.stream().mapToInt(f -> f.data.length).sum())
                .collect(Collectors.toList());
        return Futures.combineAllInOrder(grouped.stream()
                .map(g -> Futures.combineAllInOrder(g.stream()
                        .map(f -> hasher.sha256(f.data))
                        .collect(Collectors.toList())))
                .collect(Collectors.toList()))
                .thenCompose(groupsSignatures -> {
                    List<CompletableFuture<List<Multihash>>> futures = IntStream.range(0, grouped.size())
                            .mapToObj(i -> bulkUploadFragments(
                                    grouped.get(i),
                                    owner,
                                    writer.publicKeyHash,
                                    groupsSignatures.get(i)
                                            .stream()
                                            .map(sig -> writer.secret.signatureOnly(sig))
                                            .collect(Collectors.toList()),
                                    tid
                            ).thenApply(hash -> {
                                if (progressCounter != null)
                                    progressCounter.accept((long) sizes.get(i));
                                return hash;
                            })).collect(Collectors.toList());
                    return Futures.combineAllInOrder(futures)
                            .thenApply(groups -> groups.stream()
                                    .flatMap(g -> g.stream()).collect(Collectors.toList()));
                });
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
                            writer.secret.signatureOnly(blobSha), metaBlob, tid))
                    .thenCompose(blobHash -> tree.put(version.props, owner, writer, mapKey,
                            metadata.committedHash(), blobHash, tid)
                            .thenCompose(wd -> committer.commit(owner, writer, wd, version, tid)))
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
                                                            byte[] mapKey) {
        CommittedWriterData version = current.get(writer);
        return tree.get(version.props, owner, writer.publicKeyHash, mapKey)
                .thenCompose(valueHash ->
                        ! valueHash.isPresent() ? CompletableFuture.completedFuture(current) :
                                IpfsTransaction.call(owner,
                                        tid -> tree.remove(version.props, owner, writer, mapKey, valueHash, tid)
                                                .thenCompose(wd -> committer.commit(owner, writer, wd, version, tid)),
                                        dhtClient))
                .thenApply(committed -> current.withVersion(writer.publicKeyHash, committed.get(writer)));
    }

    public CompletableFuture<List<FragmentWithHash>> downloadFragments(List<Multihash> hashes,
                                                                       ProgressConsumer<Long> monitor,
                                                                       double spaceIncreaseFactor) {
        List<CompletableFuture<Optional<FragmentWithHash>>> futures = hashes.stream().parallel()
                .map(h -> (h.isIdentity() ?
                        CompletableFuture.completedFuture(Optional.of(h.getHash())) :
                        (h instanceof Cid) && ((Cid) h).codec == Cid.Codec.Raw ?
                                dhtClient.getRaw(h) :
                                dhtClient.get(h)
                                        .thenApply(cborOpt -> cborOpt.map(cbor -> ((CborObject.CborByteArray) cbor).value))) // for backwards compatibility
                        .thenApply(dataOpt -> {
                            Optional<byte[]> bytes = dataOpt;
                            bytes.ifPresent(arr -> monitor.accept((long)(arr.length / spaceIncreaseFactor)));
                            return bytes.map(data -> new FragmentWithHash(new Fragment(data), h));
                        }))
                .collect(Collectors.toList());

        return Futures.combineAllInOrder(futures)
                .thenApply(optList -> optList.stream().filter(Optional::isPresent).map(Optional::get).collect(Collectors.toList()));
    }
}
