package peergos.shared.corenode;

import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class TofuCoreNode implements CoreNode {

    public static final String KEY_STORE_NAME = ".keystore";
    private final CoreNode source;
    private final TofuKeyStore tofu;
    private final UserContext context;

    public TofuCoreNode(CoreNode source, TofuKeyStore tofu, UserContext context) {
        this.source = source;
        this.tofu = tofu;
        this.context = context;
    }

    private static String getStorePath(UserContext serializer) {
        return "/" + serializer.username + "/" + KEY_STORE_NAME;
    }

    private static CompletableFuture<TofuKeyStore> load(UserContext context) {
        if (context.username == null)
            return CompletableFuture.completedFuture(new TofuKeyStore());

        return context.getByPath(getStorePath(context)).thenCompose(fileOpt -> {
            if (! fileOpt.isPresent())
                return CompletableFuture.completedFuture(new TofuKeyStore());

            return fileOpt.get().getInputStream(context, x -> {}).thenCompose(reader -> {
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
                    return home.uploadFile(KEY_STORE_NAME, dataReader, (long) data.length, context, x-> {}, context.fragmenter());
                });
    }

    @Override
    public CompletableFuture<Optional<PublicSigningKey>> getPublicKey(String username) {
        Optional<PublicSigningKey> local = tofu.getPublicKey(username);
        if (local.isPresent())
            return CompletableFuture.completedFuture(local);
        return source.getChain(username)
                .thenCompose(chain -> {
                    tofu.updateChain(username, chain);
                    return commit().thenApply(x -> tofu.getPublicKey(username));
                });
    }

    @Override
    public CompletableFuture<String> getUsername(PublicSigningKey key) {
        Optional<String> local = tofu.getUsername(key);
        if (local.isPresent())
            return CompletableFuture.completedFuture(local.get());
        return source.getUsername(key)
                .thenCompose(username -> {
                    return source.getChain(username).thenCompose(chain -> {
                        tofu.updateChain(username, chain);
                        return commit().thenApply(x -> username);
                    });
                });
    }

    @Override
    public CompletableFuture<List<UserPublicKeyLink>> getChain(String username) {
        List<UserPublicKeyLink> localChain = tofu.getChain(username);
        if (! localChain.isEmpty())
            return CompletableFuture.completedFuture(localChain);
        return source.getChain(username)
                .thenCompose(chain -> {
                    tofu.updateChain(username, chain);
                    return commit().thenApply(x -> tofu.getChain(username));
                });
    }

    @Override
    public CompletableFuture<Boolean> updateChain(String username, List<UserPublicKeyLink> chain) {
        tofu.updateChain(username, chain);
        return commit().thenCompose(x -> source.updateChain(username, chain));
    }

    @Override
    public CompletableFuture<List<String>> getUsernames(String prefix) {
        return source.getUsernames(prefix);
    }

    @Override
    public CompletableFuture<Boolean> followRequest(PublicSigningKey target, byte[] encryptedPermission) {
        return source.followRequest(target, encryptedPermission);
    }

    @Override
    public CompletableFuture<byte[]> getFollowRequests(PublicSigningKey owner) {
        return source.getFollowRequests(owner);
    }

    @Override
    public CompletableFuture<Boolean> removeFollowRequest(PublicSigningKey owner, byte[] data) {
        return source.removeFollowRequest(owner, data);
    }

    @Override
    public void close() throws IOException {}
}
