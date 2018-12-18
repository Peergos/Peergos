package peergos.shared;
import java.util.logging.*;

import jsinterop.annotations.*;
import peergos.client.*;
import peergos.server.corenode.*;
import peergos.server.mutable.*;
import peergos.server.social.*;
import peergos.server.storage.*;
import peergos.shared.cbor.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.mutable.*;
import peergos.shared.social.*;
import peergos.shared.storage.*;
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
    @JsProperty
    public final List<String> usernames;
    private final LocalDateTime creationTime;
    private final boolean isJavascript;

    public NetworkAccess(CoreNode coreNode, SocialNetwork social, ContentAddressedStorage dhtClient, MutablePointers mutable, MutableTree tree, List<String> usernames) {
        this(coreNode, social, dhtClient, mutable, tree, usernames, false);
    }

    public NetworkAccess(CoreNode coreNode, SocialNetwork social, ContentAddressedStorage dhtClient, MutablePointers mutable, MutableTree tree, List<String> usernames, boolean isJavascript) {
        this.coreNode = coreNode;
        this.social = social;
        this.dhtClient = new HashVerifyingStorage(dhtClient);
        this.mutable = mutable;
        this.tree = tree;
        this.usernames = usernames;
        this.creationTime = LocalDateTime.now();
        this.isJavascript = isJavascript;
    }

    public boolean isJavascript() {
    	return isJavascript;
    }

    public NetworkAccess withCorenode(CoreNode newCore) {
        return new NetworkAccess(newCore, social, dhtClient, mutable, tree, usernames, isJavascript);
    }

    @JsMethod
    public CompletableFuture<Boolean> isUsernameRegistered(String username) {
        if (usernames.contains(username))
            return CompletableFuture.completedFuture(true);
        return coreNode.getChain(username).thenApply(chain -> chain.size() > 0);
    }

    public NetworkAccess clear() {
        return new NetworkAccess(coreNode, social, dhtClient, mutable, new MutableTreeImpl(mutable, dhtClient), usernames, isJavascript);
    }

    public NetworkAccess withMutablePointerCache(int ttl) {
        CachingPointers mutable = new CachingPointers(this.mutable, ttl);
        return new NetworkAccess(coreNode, social, dhtClient, mutable, new MutableTreeImpl(mutable, dhtClient), usernames, isJavascript);
    }

    public static CoreNode buildProxyingCorenode(HttpPoster poster, Multihash pkiServerNodeId) {
        return new HTTPCoreNode(poster, pkiServerNodeId);
    }

    public static CoreNode buildDirectCorenode(HttpPoster poster) {
        return new HTTPCoreNode(poster);
    }

    public static ContentAddressedStorage buildLocalDht(HttpPoster apiPoster) {
        return new CachingStorage(new ContentAddressedStorage.HTTP(apiPoster), 10_000, 50 * 1024);
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
        ContentAddressedStorage localDht = buildLocalDht(apiPoster);

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
                    CoreNode core = buildProxyingCorenode(p2pPoster, pkiServerNodeId);
                    core.getUsernames("").thenCompose(usernames ->
                            build(core, localDht, apiPoster, p2pPoster, usernames, false, isJavascript)
                                    .thenApply(result::complete))
                            .exceptionally(t2 -> {
                                result.completeExceptionally(t2);
                                return true;
                            });
                    return null;
                });
        return result;
    }

    public static CompletableFuture<NetworkAccess> build(CoreNode core,
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
                    return build(p2pDht, core, p2pMutable, p2pSocial, usernames, isJavascript);
                });
    }

    public static NetworkAccess build(ContentAddressedStorage dht,
                                      CoreNode coreNode,
                                                         MutablePointers mutable,
                                                         SocialNetwork social,
                                                         List<String> usernames,
                                                         boolean isJavascript) {
        MutableTree btree = new MutableTreeImpl(mutable, dht);
        return new NetworkAccess(coreNode, social, dht, mutable, btree, usernames, isJavascript);
    }

    public static CompletableFuture<NetworkAccess> buildJava(URL target) {
        JavaPoster poster = new JavaPoster(target);
        CoreNode direct = buildDirectCorenode(poster);
        try {
            List<String> usernames = direct.getUsernames("").get();
            ContentAddressedStorage localDht = buildLocalDht(poster);
            return build(direct, localDht, poster, poster, usernames, true, false);
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

    public CompletableFuture<List<RetrievedFilePointer>> retrieveAllMetadata(List<Capability> links) {
        List<CompletableFuture<Optional<RetrievedFilePointer>>> all = links.stream()
                .map(link -> {
                    Location loc = link.location;
                    return tree.get(loc.owner, loc.writer, loc.getMapKey())
                            .thenCompose(key -> {
                                if (key.isPresent())
                                    return dhtClient.get(key.get())
                                            .thenApply(dataOpt ->  dataOpt
                                                    .map(cbor -> new RetrievedFilePointer(
                                                            link,
                                                            CryptreeNode.fromCbor(cbor, key.get()))));
                                LOG.severe("Couldn't download link at: " + loc);
                                Optional<RetrievedFilePointer> result = Optional.empty();
                                return CompletableFuture.completedFuture(result);
                            });
                }).collect(Collectors.toList());

        return Futures.combineAll(all).thenApply(optSet -> optSet.stream()
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList()));
    }

    public CompletableFuture<Set<FileTreeNode>> retrieveAll(List<EntryPoint> entries) {
        return Futures.reduceAll(entries, Collections.emptySet(),
                (set, entry) -> retrieveEntryPoint(entry)
                        .thenApply(opt ->
                                opt.map(f -> Stream.concat(set.stream(), Stream.of(f)).collect(Collectors.toSet()))
                                        .orElse(set)),
                (a, b) -> Stream.concat(a.stream(), b.stream()).collect(Collectors.toSet()));
    }

    public CompletableFuture<Optional<FileTreeNode>> retrieveEntryPoint(EntryPoint e) {
        return downloadEntryPoint(e)
                .thenApply(faOpt ->faOpt.map(fa -> new FileTreeNode(new RetrievedFilePointer(e.pointer, fa), e.owner,
                        e.readers, e.writers, e.pointer.writer)))
                .exceptionally(t -> Optional.empty());
    }

    private CompletableFuture<Optional<CryptreeNode>> downloadEntryPoint(EntryPoint entry) {
        // download the metadata blob for this entry point
        return tree.get(entry.pointer.location.owner, entry.pointer.location.writer, entry.pointer.location.getMapKey()).thenCompose(btreeValue -> {
            if (btreeValue.isPresent())
                return dhtClient.get(btreeValue.get())
                        .thenApply(value -> value.map(cbor -> CryptreeNode.fromCbor(cbor,  btreeValue.get())));
            return CompletableFuture.completedFuture(Optional.empty());
        });
    }

    private CompletableFuture<Multihash> uploadFragment(Fragment f, PublicKeyHash owner, PublicKeyHash writer, byte[] signature) {
        return dhtClient.putRaw(owner, writer, signature, f.data);
    }

    private CompletableFuture<List<Multihash>> bulkUploadFragments(List<Fragment> fragments, PublicKeyHash owner, PublicKeyHash writer, List<byte[]> signatures) {
        return dhtClient.putRaw(owner, writer, signatures, fragments
                .stream()
                .map(f -> f.data)
                .collect(Collectors.toList()));
    }

    public CompletableFuture<List<Multihash>> uploadFragments(List<Fragment> fragments,
                                                              PublicKeyHash owner,
                                                              SigningPrivateKeyAndPublicHash writer,
                                                              ProgressConsumer<Long> progressCounter,
                                                              double spaceIncreaseFactor) {
        // upload in groups of 10. This means in a browser we have 6 upload threads with erasure coding on, or 4 without
        int FRAGMENTs_PER_QUERY = 1;
        List<List<Fragment>> grouped = IntStream.range(0, (fragments.size() + FRAGMENTs_PER_QUERY - 1) / FRAGMENTs_PER_QUERY)
                .mapToObj(i -> fragments.stream().skip(FRAGMENTs_PER_QUERY * i).limit(FRAGMENTs_PER_QUERY).collect(Collectors.toList()))
                .collect(Collectors.toList());
        List<CompletableFuture<List<Multihash>>> futures = grouped.stream()
                .map(g -> bulkUploadFragments(
                        g,
                        owner,
                        writer.publicKeyHash,
                        g.stream().map(f -> writer.secret.signatureOnly(f.data)).collect(Collectors.toList())
                ).thenApply(hash -> {
                    if (progressCounter != null)
                        progressCounter.accept((long)(g.stream().mapToInt(f -> f.data.length).sum() / spaceIncreaseFactor));
                    return hash;
                })).collect(Collectors.toList());
        return Futures.combineAllInOrder(futures)
                .thenApply(groups -> groups.stream()
                        .flatMap(g -> g.stream())
                        .collect(Collectors.toList()));
    }

    public CompletableFuture<Multihash> uploadChunk(CryptreeNode metadata, Location location, SigningPrivateKeyAndPublicHash writer) {
        if (! writer.publicKeyHash.equals(location.writer))
            throw new IllegalStateException("Non matching location writer and signing writer key!");
        try {
            byte[] metaBlob = metadata.serialize();
            return dhtClient.put(location.owner, location.writer, writer.secret.signatureOnly(metaBlob), metaBlob)
                    .thenCompose(blobHash -> tree.put(location.owner, writer, location.getMapKey(), metadata.committedHash(), blobHash)
                            .thenApply(res -> blobHash));
        } catch (Exception e) {
            LOG.severe(e.getMessage());
            throw new RuntimeException(e);
        }
    }

    public CompletableFuture<Optional<CryptreeNode>> getMetadata(Location loc) {
        if (loc == null)
            return CompletableFuture.completedFuture(Optional.empty());
        return tree.get(loc.owner, loc.writer, loc.getMapKey()).thenCompose(blobHash -> {
            if (!blobHash.isPresent())
                return CompletableFuture.completedFuture(Optional.empty());
            return dhtClient.get(blobHash.get())
                    .thenApply(rawOpt -> rawOpt.map(cbor -> CryptreeNode.fromCbor(cbor, blobHash.get())));
        });
    }

    public CompletableFuture<List<FragmentWithHash>> downloadFragments(List<Multihash> hashes,
                                                                       ProgressConsumer<Long> monitor,
                                                                       double spaceIncreaseFactor) {
        List<CompletableFuture<Optional<FragmentWithHash>>> futures = hashes.stream().parallel()
                .map(h -> ((h instanceof Cid) && ((Cid) h).codec == Cid.Codec.Raw ?
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

    /**
     * Pin all files associated with all the keys of a user. For
     * self-hosting.
     *
     * @param username
     */
    public void pinAllUserFiles(String username) throws ExecutionException, InterruptedException {
        pinAllUserFiles(username, coreNode, mutable, dhtClient);
    }

    public static void pinAllUserFiles(String username, CoreNode coreNode, MutablePointers mutable, ContentAddressedStorage dhtClient) throws ExecutionException, InterruptedException {
        Set<PublicKeyHash> ownedKeysRecursive = WriterData.getOwnedKeysRecursive(username, coreNode, mutable, dhtClient);
        Optional<PublicKeyHash> ownerOpt = coreNode.getPublicKeyHash(username).get();
        if (! ownerOpt.isPresent())
            throw new IllegalStateException("Couldn't retrieve public key for " + username);
        PublicKeyHash owner = ownerOpt.get();
        for (PublicKeyHash keyHash: ownedKeysRecursive) {
            Multihash casKeyHash = mutable.getPointerTarget(owner, keyHash, dhtClient).get().get();
            dhtClient.recursivePin(owner, casKeyHash).get();
        }
    }
}
