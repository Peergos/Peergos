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

    public static CompletableFuture<NetworkAccess> build(HttpPoster poster, boolean isJavascript) {
        int cacheTTL = 7_000;
        LOG.info("Using caching corenode with TTL: " + cacheTTL + " mS");
        CoreNode coreNode = new HTTPCoreNode(poster);
        SocialNetwork social = new HttpSocialNetwork(poster, poster);
        MutablePointers mutable = new CachingPointers(new HttpMutablePointers(poster, poster), cacheTTL);

        // allow 10MiB of ram for caching tree entries
        ContentAddressedStorage dht = new CachingStorage(new ContentAddressedStorage.HTTP(poster), 10_000, 50 * 1024);
        MutableTree btree = new MutableTreeImpl(mutable, dht);
        return coreNode.getUsernames("")
                .thenApply(usernames -> new NetworkAccess(coreNode, social, dht, mutable, btree, usernames, isJavascript));
    }

    @JsMethod
    public static CompletableFuture<NetworkAccess> buildJS() {
        System.setOut(new ConsolePrintStream());
        System.setErr(new ConsolePrintStream());
        return build(new JavaScriptPoster(), true);
    }

    public static CompletableFuture<NetworkAccess> buildJava(URL target) {
        return build(new JavaPoster(target), false);
    }

    public static CompletableFuture<NetworkAccess> buildJava(int targetPort) {
        try {
            return buildJava(new URL("http://localhost:" + targetPort + "/"));
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }

    public CompletableFuture<List<RetrievedFilePointer>> retrieveAllMetadata(List<SymmetricLocationLink> links,
                                                                                    SymmetricKey baseKey) {
        List<CompletableFuture<Optional<RetrievedFilePointer>>> all = links.stream()
                .map(link -> {
                    Location loc = link.targetLocation(baseKey);
                    return tree.get(loc.writer, loc.getMapKey())
                            .thenCompose(key -> {
                                if (key.isPresent())
                                    return dhtClient.get(key.get())
                                            .thenApply(dataOpt ->  dataOpt
                                                    .map(cbor -> new RetrievedFilePointer(
                                                            link.toReadableFilePointer(baseKey),
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
                        e.readers, e.writers, e.pointer.writer)));
    }

    private CompletableFuture<Optional<CryptreeNode>> downloadEntryPoint(EntryPoint entry) {
        // download the metadata blob for this entry point
        return tree.get(entry.pointer.location.writer, entry.pointer.location.getMapKey()).thenCompose(btreeValue -> {
            if (btreeValue.isPresent())
                return dhtClient.get(btreeValue.get())
                        .thenApply(value -> value.map(cbor -> CryptreeNode.fromCbor(cbor,  btreeValue.get())));
            return CompletableFuture.completedFuture(Optional.empty());
        });
    }

    private CompletableFuture<Multihash> uploadFragment(Fragment f, PublicKeyHash writer, byte[] signature) {
        return dhtClient.putRaw(writer, signature, f.data);
    }

    private CompletableFuture<List<Multihash>> bulkUploadFragments(List<Fragment> fragments, PublicKeyHash writer, List<byte[]> signatures) {
        return dhtClient.putRaw(writer, signatures, fragments
                .stream()
                .map(f -> f.data)
                .collect(Collectors.toList()));
    }

    public CompletableFuture<List<Multihash>> uploadFragments(List<Fragment> fragments, SigningPrivateKeyAndPublicHash writer,
                                                              ProgressConsumer<Long> progressCounter, double spaceIncreaseFactor) {
        // upload in groups of 10. This means in a browser we have 6 upload threads with erasure coding on, or 4 without
        int FRAGMENTs_PER_QUERY = 1;
        List<List<Fragment>> grouped = IntStream.range(0, (fragments.size() + FRAGMENTs_PER_QUERY - 1) / FRAGMENTs_PER_QUERY)
                .mapToObj(i -> fragments.stream().skip(FRAGMENTs_PER_QUERY * i).limit(FRAGMENTs_PER_QUERY).collect(Collectors.toList()))
                .collect(Collectors.toList());
        List<CompletableFuture<List<Multihash>>> futures = grouped.stream()
                .map(g -> bulkUploadFragments(
                        g,
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
            return dhtClient.put(location.writer, writer.secret.signatureOnly(metaBlob), metaBlob)
                    .thenCompose(blobHash -> tree.put(writer, location.getMapKey(), metadata.committedHash(), blobHash)
                            .thenApply(res -> blobHash));
        } catch (Exception e) {
            LOG.severe(e.getMessage());
            throw new RuntimeException(e);
        }
    }

    public CompletableFuture<Optional<CryptreeNode>> getMetadata(Location loc) {
        if (loc == null)
            return CompletableFuture.completedFuture(Optional.empty());
        return tree.get(loc.writer, loc.getMapKey()).thenCompose(blobHash -> {
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

        for (PublicKeyHash keyHash: ownedKeysRecursive) {
            Multihash casKeyHash = mutable.getPointerTarget(keyHash, dhtClient).get().get();
            dhtClient.recursivePin(casKeyHash).get();
        }
    }
}
