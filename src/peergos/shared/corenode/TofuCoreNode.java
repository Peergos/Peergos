package peergos.shared.corenode;

import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** The TOFU core node stores a local copy of all identity key mappings retrieved from the pki ina TOFU manner.
 *  The store is at /$username/.keystore in the user's Peergos space.
 */
public class TofuCoreNode implements CoreNode {

    public static final String KEY_STORE_NAME = ".keystore";
    private final CoreNode source;
    private final TofuKeyStore tofu;
    private final NetworkAccess network;
    private final Crypto crypto;
    private FileWrapper backingFile;

    public TofuCoreNode(CoreNode source, TofuKeyStore tofu, FileWrapper backingFile, NetworkAccess network, Crypto crypto) {
        this.source = source;
        this.tofu = tofu;
        this.backingFile = backingFile;
        this.network = network;
        this.crypto = crypto;
    }

    /**
     *
     * @param username
     * @param root
     * @param network
     * @param crypto
     * @return The TOFU core node for this user
     */
    public static CompletableFuture<TofuCoreNode> load(String username, TrieNode root, NetworkAccess network, Crypto crypto) {
        if (username == null)
            throw new IllegalStateException("Cannot build a tofu keystore if not logged in!");

        return root.getByPath(username, crypto.hasher, network)
                .thenApply(Optional::get)
                .thenCompose(homeDir -> homeDir.getChildrenWithSameWriter(crypto.hasher, network)
                        .thenCompose(children -> {
                            Optional<FileWrapper> keystoreOpt = children.stream().filter(c -> c.getName().equals(KEY_STORE_NAME)).findFirst();
                            if (keystoreOpt.isEmpty()) {
                                // initialize empty keystore
                                TofuKeyStore store = new TofuKeyStore();
                                byte[] raw = store.serialize();
                                return homeDir.uploadAndReturnFile(KEY_STORE_NAME, AsyncReader.build(raw), raw.length,
                                        network, crypto)
                                        .thenApply(f -> new TofuCoreNode(network.coreNode, store, f, network, crypto));
                            }

                            return keystoreOpt.get().getInputStream(network, crypto, x -> {}).thenCompose(reader -> {
                                byte[] storeData = new byte[(int) keystoreOpt.get().getSize()];
                                return reader.readIntoArray(storeData, 0, storeData.length)
                                        .thenApply(x -> new TofuCoreNode(network.coreNode,
                                                TofuKeyStore.fromCbor(CborObject.fromByteArray(storeData)),
                                                keystoreOpt.get(), network, crypto));
                            });
                        }));
    }

    private synchronized CompletableFuture<Boolean> commit() {
        byte[] data = tofu.serialize();
        AsyncReader.ArrayBacked dataReader = new AsyncReader.ArrayBacked(data);
        return backingFile.overwriteFile(dataReader, data.length, network, crypto, x -> {})
                .thenApply(f -> {
                    this.backingFile = f;
                    return true;
                });
    }

    @Override
    public CompletableFuture<Boolean> updateUser(String username) {
        return source.getChain(username)
                .thenCompose(chain -> tofu.updateChain(username, chain, network.dhtClient)
                        .thenCompose(x -> commit()));
    }

    @Override
    public CompletableFuture<Optional<PublicKeyHash>> getPublicKeyHash(String username) {
        Optional<PublicKeyHash> local = tofu.getPublicKey(username);
        if (local.isPresent())
            return CompletableFuture.completedFuture(local);
        return source.getChain(username)
                .thenCompose(chain -> {
                        if(chain.isEmpty()) {
                            return CompletableFuture.completedFuture(false);
                        } else {
                            return tofu.updateChain(username, chain, network.dhtClient)
                                    .thenCompose(x -> commit());
                        }
                    }
                ).thenApply(x -> x == false ? Optional.empty() : tofu.getPublicKey(username));
    }

    @Override
    public CompletableFuture<String> getUsername(PublicKeyHash key) {
        Optional<String> local = tofu.getUsername(key);
        if (local.isPresent())
            return CompletableFuture.completedFuture(local.get());
        return source.getUsername(key)
                .thenCompose(username -> source.getChain(username)
                        .thenCompose(chain -> tofu.updateChain(username, chain, network.dhtClient)
                                .thenCompose(x -> commit())
                                .thenApply(x -> username)));
    }

    @Override
    public CompletableFuture<List<UserPublicKeyLink>> getChain(String username) {
        List<UserPublicKeyLink> localChain = tofu.getChain(username);
        if (! localChain.isEmpty())
            return CompletableFuture.completedFuture(localChain);
        return source.getChain(username)
                .thenCompose(chain -> tofu.updateChain(username, chain, network.dhtClient)
                        .thenCompose(x -> commit())
                        .thenApply(x -> tofu.getChain(username)));
    }

    @Override
    public CompletableFuture<Boolean> updateChain(String username, List<UserPublicKeyLink> chain) {
        return tofu.updateChain(username, chain, network.dhtClient)
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
