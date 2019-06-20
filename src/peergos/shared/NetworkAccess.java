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

    protected NetworkAccess(CoreNode coreNode,
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
        this.dhtClient = new HashVerifyingStorage(dhtClient, isJavascript ? new ScryptJS() : new ScryptJava());
        this.mutable = mutable;
        this.tree = tree;
        this.synchronizer = synchronizer;
        this.instanceAdmin = instanceAdmin;
        this.spaceUsage = spaceUsage;
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
        WriteSynchronizer synchronizer = new WriteSynchronizer(mutable, dhtClient);
        MutableTree mutableTree = new MutableTreeImpl(mutable, dhtClient, synchronizer);
        return new NetworkAccess(coreNode, social, dhtClient, mutable, mutableTree, synchronizer, instanceAdmin, spaceUsage, usernames, isJavascript);
    }

    public NetworkAccess withMutablePointerCache(int ttl) {
        CachingPointers mutable = new CachingPointers(this.mutable, ttl);
        WriteSynchronizer synchronizer = new WriteSynchronizer(mutable, dhtClient);
        MutableTree mutableTree = new MutableTreeImpl(mutable, dhtClient, synchronizer);
        return new NetworkAccess(coreNode, social, dhtClient, mutable, mutableTree, synchronizer, instanceAdmin, spaceUsage, usernames, isJavascript);
    }

    public static CoreNode buildProxyingCorenode(HttpPoster poster, Multihash pkiServerNodeId) {
        return new HTTPCoreNode(poster, pkiServerNodeId);
    }

    public static CoreNode buildDirectCorenode(HttpPoster poster) {
        return new HTTPCoreNode(poster);
    }

    public static ContentAddressedStorage buildLocalDht(HttpPoster apiPoster, boolean isPeergosServer) {
        return new CachingStorage(new ContentAddressedStorage.HTTP(apiPoster, isPeergosServer), 10_000, 50 * 1024);
    }

    @JsMethod
    public static CompletableFuture<NetworkAccess> buildJS(String pkiNodeId) {
        Multihash pkiServerNodeId = Cid.decode(pkiNodeId);
        System.setOut(new ConsolePrintStream());
        System.setErr(new ConsolePrintStream());
        JavaScriptPoster relative = new JavaScriptPoster(false);
        JavaScriptPoster absolute = new JavaScriptPoster(true);

        return isPeergosServer(relative)
                .thenApply(isPeergosServer -> isPeergosServer ? relative : absolute)
                .thenCompose(poster -> build(poster, poster, pkiServerNodeId, true))
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
        return build(apiPoster, p2pPoster, pkiServerNodeId, false);
    }

    public static CompletableFuture<NetworkAccess> build(HttpPoster apiPoster, HttpPoster p2pPoster, Multihash pkiServerNodeId, boolean isJavascript) {
        ContentAddressedStorage localDht = buildLocalDht(apiPoster, true);

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
                    ContentAddressedStorage p2pDht = isPeergosServer ?
                            localDht :
                            new ContentAddressedStorage.Proxying(localDht, proxingDht, nodeId, core);
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
        WriteSynchronizer synchronizer = new WriteSynchronizer(mutable, dht);
        MutableTree btree = new MutableTreeImpl(mutable, dht, synchronizer);
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
        WriteSynchronizer synchronizer = new WriteSynchronizer(mutable, storage);
        MutableTree mutableTree = new MutableTreeImpl(mutable, storage, synchronizer);
        return CompletableFuture.completedFuture(new NetworkAccess(core, null, storage, mutable, mutableTree,
                synchronizer, null, null, Collections.emptyList(), false));
    }

    public CompletableFuture<Optional<RetrievedCapability>> retrieveMetadata(AbsoluteCapability cap, Snapshot version) {
        return retrieveAllMetadata(Collections.singletonList(cap), version)
                .thenApply(res -> res.isEmpty() ? Optional.empty() : Optional.of(res.get(0)));
    }

    public CompletableFuture<List<RetrievedCapability>> retrieveAllMetadata(List<AbsoluteCapability> links) {
        List<CompletableFuture<Optional<RetrievedCapability>>> all = links.stream()
                .map(link -> {
                    PublicKeyHash owner = link.owner;
                    PublicKeyHash writer = link.writer;
                    byte[] mapKey = link.getMapKey();
                    return tree.get(owner, writer, mapKey)
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
                .thenCompose(version -> getMetadata(version.get(e.pointer.writer).props, e.pointer)
                        .thenApply(faOpt ->faOpt.map(fa -> new FileWrapper(Optional.empty(),
                                new RetrievedCapability(e.pointer, fa),
                                e.pointer.wBaseKey.map(wBase -> fa.getSigner(e.pointer.rBaseKey, wBase, Optional.empty())),
                                e.ownerName, version))))
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



    public CompletableFuture<Optional<FileWrapper>> getFile(Snapshot version,
                                                            AbsoluteCapability cap,
                                                            Optional<SigningPrivateKeyAndPublicHash> entryWriter,
                                                            String ownerName) {
        return getMetadata(version.get(cap.writer).props, cap)
                .thenApply(faOpt -> faOpt.map(fa -> new FileWrapper(Optional.empty(),
                        new RetrievedCapability(cap, fa),
                        cap.wBaseKey.map(wBase -> fa.getSigner(cap.rBaseKey, wBase, entryWriter)), ownerName, version)));
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
        List<CompletableFuture<List<Multihash>>> futures = grouped.stream()
                .map(g -> bulkUploadFragments(
                        g,
                        owner,
                        writer.publicKeyHash,
                        g.stream().map(f -> writer.secret.signatureOnly(f.data)).collect(Collectors.toList()),
                        tid
                ).thenApply(hash -> {
                    if (progressCounter != null)
                        progressCounter.accept((long)(g.stream().mapToInt(f -> f.data.length).sum()));
                    return hash;
                })).collect(Collectors.toList());
        return Futures.combineAllInOrder(futures)
                .thenApply(groups -> groups.stream()
                        .flatMap(g -> g.stream())
                        .collect(Collectors.toList()));
    }

    public CompletableFuture<Multihash> uploadChunk(CryptreeNode metadata,
                                                    PublicKeyHash owner,
                                                    byte[] mapKey,
                                                    SigningPrivateKeyAndPublicHash writer,
                                                    TransactionId tid) {
        try {
            System.out.println("Uploading chunk: " + (metadata.isDirectory() ? "dir" : "file")
                    + " at " + ArrayOps.bytesToHex(mapKey)
                    + " with " + metadata.toCbor().links().size() + " fragments");
            byte[] metaBlob = metadata.serialize();
            return dhtClient.put(owner, writer.publicKeyHash, writer.secret.signatureOnly(metaBlob), metaBlob, tid)
                    .thenCompose(blobHash -> tree.put(owner, writer, mapKey, metadata.committedHash(), blobHash)
                            .thenApply(res -> blobHash));
        } catch (Exception e) {
            LOG.severe(e.getMessage());
            throw new RuntimeException(e);
        }
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
            System.out.println("Uploading chunk: " + (metadata.isDirectory() ? "dir" : "file")
                    + " at " + ArrayOps.bytesToHex(mapKey)
                    + " with " + metadata.toCbor().links().size() + " fragments");
            byte[] metaBlob = metadata.serialize();
            CommittedWriterData version = current.get(writer);
            return dhtClient.put(owner, writer.publicKeyHash, writer.secret.signatureOnly(metaBlob), metaBlob, tid)
                    .thenCompose(blobHash -> tree.put(version.props, owner, writer, mapKey, metadata.committedHash(), blobHash, tid)
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
                                        dhtClient));
    }

    public CompletableFuture<Optional<CryptreeNode>> getMetadata(AbsoluteCapability cap) {
        return tree.get(cap.owner, cap.writer, cap.getMapKey()).thenCompose(blobHash -> {
            if (!blobHash.isPresent())
                return CompletableFuture.completedFuture(Optional.empty());
            return dhtClient.get(blobHash.get())
                    .thenApply(rawOpt -> rawOpt.map(cbor -> CryptreeNode.fromCbor(cbor, cap.rBaseKey, blobHash.get())));
        });
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
