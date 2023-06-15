package peergos.server.mutable;

import peergos.shared.crypto.hash.*;
import peergos.shared.mutable.*;

import java.util.*;
import java.util.concurrent.*;

public class BlockingMutablePointers implements MutablePointers {
    private final MutablePointers source;
    private final PublicKeyBlackList blacklist;

    public BlockingMutablePointers(MutablePointers source, PublicKeyBlackList blacklist) {
        this.source = source;
        this.blacklist = blacklist;
    }

    @Override
    public CompletableFuture<Boolean> setPointer(PublicKeyHash owner, PublicKeyHash writer, byte[] writerSignedBtreeRootHash) {
        if (blacklist.isAllowed(writer))
            return source.setPointer(owner, writer, writerSignedBtreeRootHash);
        CompletableFuture<Boolean> res = new CompletableFuture<>();
        res.completeExceptionally(new IllegalStateException("This Peergos subspace has been banned from this server"));
        return res;
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getPointer(PublicKeyHash owner, PublicKeyHash writer) {
        if (blacklist.isAllowed(writer))
            return source.getPointer(owner, writer);
        CompletableFuture<Optional<byte[]>> res = new CompletableFuture<>();
        res.completeExceptionally(new IllegalStateException("This Peergos subspace has been banned from this server"));
        return res;
    }

    @Override
    public MutablePointers clearCache() {
        return this;
    }
}
