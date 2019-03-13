package peergos.shared.corenode;

import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.random.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class TofuCoreNode implements CoreNode {

    public static final String KEY_STORE_NAME = ".keystore";
    private final CoreNode source;
    private final TofuKeyStore tofu;
    private UserContext context;

    public TofuCoreNode(CoreNode source, TofuKeyStore tofu) {
        this.source = source;
        this.tofu = tofu;
    }

    public void setContext(UserContext context) {
        this.context = context;
    }

    private static String getStorePath(String username) {
        return "/" + username + "/" + KEY_STORE_NAME;
    }

    public static CompletableFuture<TofuKeyStore> load(String username, TrieNode root, NetworkAccess network, SafeRandom random) {
        if (username == null)
            return CompletableFuture.completedFuture(new TofuKeyStore());

        return root.getByPath(getStorePath(username), network).thenCompose(fileOpt -> {
            if (! fileOpt.isPresent())
                return CompletableFuture.completedFuture(new TofuKeyStore());

            return fileOpt.get().getInputStream(network, random, x -> {}).thenCompose(reader -> {
                byte[] storeData = new byte[(int) fileOpt.get().getSize()];
                return reader.readIntoArray(storeData, 0, storeData.length)
                        .thenApply(x -> TofuKeyStore.fromCbor(CborObject.fromByteArray(storeData)));
            });
        });
    }

    private CompletableFuture<Boolean> commit() {
        return context.getUserRoot()
                .thenCompose(home -> {
                    byte[] data = tofu.serialize();
                    AsyncReader.ArrayBacked dataReader = new AsyncReader.ArrayBacked(data);
                    return home.uploadFileSection(KEY_STORE_NAME, dataReader, true, 0, (long) data.length,
                            Optional.empty(), true, context.network, context.crypto.random,
                            context.crypto.hasher, x -> {}, context.fragmenter(),
                            home.generateChildLocationsFromSize(data.length, context.crypto.random));
                }).thenApply(x -> true);
    }

    public CompletableFuture<Boolean> updateUser(String username) {
        return source.getChain(username)
                .thenCompose(chain -> tofu.updateChain(username, chain, context.network.dhtClient)
                        .thenCompose(x -> commit()));
    }

    @Override
    public CompletableFuture<Optional<PublicKeyHash>> getPublicKeyHash(String username) {
        Optional<PublicKeyHash> local = tofu.getPublicKey(username);
        if (local.isPresent())
            return CompletableFuture.completedFuture(local);
        return source.getChain(username)
                .thenCompose(chain -> tofu.updateChain(username, chain, context.network.dhtClient)
                        .thenCompose(x -> commit()))
                .thenApply(x -> tofu.getPublicKey(username));
    }

    @Override
    public CompletableFuture<String> getUsername(PublicKeyHash key) {
        Optional<String> local = tofu.getUsername(key);
        if (local.isPresent())
            return CompletableFuture.completedFuture(local.get());
        return source.getUsername(key)
                .thenCompose(username -> source.getChain(username)
                        .thenCompose(chain -> tofu.updateChain(username, chain, context.network.dhtClient)
                                .thenCompose(x -> commit())
                                .thenApply(x -> username)));
    }

    @Override
    public CompletableFuture<List<UserPublicKeyLink>> getChain(String username) {
        List<UserPublicKeyLink> localChain = tofu.getChain(username);
        if (! localChain.isEmpty())
            return CompletableFuture.completedFuture(localChain);
        return source.getChain(username)
                .thenCompose(chain -> tofu.updateChain(username, chain, context.network.dhtClient)
                        .thenCompose(x -> commit())
                        .thenApply(x -> tofu.getChain(username)));
    }

    @Override
    public CompletableFuture<Boolean> updateChain(String username, List<UserPublicKeyLink> chain) {
        return tofu.updateChain(username, chain, context.network.dhtClient)
                .thenCompose(x -> commit())
                .thenCompose(x -> source.updateChain(username, chain));
    }

    @Override
    public CompletableFuture<List<String>> getUsernames(String prefix) {
        return source.getUsernames(prefix);
    }

    @Override
    public void close() throws IOException {}
}
