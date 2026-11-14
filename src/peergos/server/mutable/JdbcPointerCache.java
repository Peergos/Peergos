package peergos.server.mutable;

import peergos.server.corenode.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.mutable.*;
import peergos.shared.storage.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;

public class JdbcPointerCache implements PointerCache {

    private final JdbcIpnsAndSocial store;
    private final ContentAddressedStorage storage;

    public JdbcPointerCache(JdbcIpnsAndSocial store, ContentAddressedStorage storage) {
        this.store = store;
        this.storage = storage;
    }

    @Override
    public synchronized CompletableFuture<Boolean> put(PublicKeyHash owner, PublicKeyHash writer, byte[] signedUpdate) {
        return store.getPointer(writer)
                .thenCompose(current -> storage.getSigningKey(owner, writer).thenCompose(signerOpt -> {
                    if (signerOpt.isEmpty())
                        throw new IllegalStateException("Couldn't retrieve signing key!");
                    if (doUpdate(current, signedUpdate, signerOpt.get()).join())
                        return store.setPointer(writer, current, signedUpdate);
                    return Futures.of(false);
                }));
    }

    @Override
    public CompletableFuture<Optional<byte[]>> get(PublicKeyHash owner, PublicKeyHash writer) {
        return store.getPointer(writer);
    }
}
