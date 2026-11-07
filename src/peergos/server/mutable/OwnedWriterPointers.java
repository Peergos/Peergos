package peergos.server.mutable;

import peergos.shared.crypto.hash.*;
import peergos.shared.mutable.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Refuses pointer updates from keys the owner no longer owns, such as one whose write access was revoked. Blocks
 *  are checked the same way as they are written, but a pointer can be moved without writing any.
 */
public class OwnedWriterPointers implements MutablePointers {

    private final MutablePointers target;
    private final BiFunction<PublicKeyHash, SignedPointerUpdate, Boolean> isOwned;

    public OwnedWriterPointers(MutablePointers target, BiFunction<PublicKeyHash, SignedPointerUpdate, Boolean> isOwned) {
        this.target = target;
        this.isOwned = isOwned;
    }

    private void check(PublicKeyHash owner, SignedPointerUpdate update) {
        if (! isOwned.apply(owner, update))
            throw new IllegalStateException("Key not allowed to write to this server: " + update.writer);
    }

    @Override
    public CompletableFuture<Boolean> setPointer(PublicKeyHash owner, PublicKeyHash writer, byte[] writerSignedBtreeRootHash) {
        try {
            check(owner, new SignedPointerUpdate(writer, writerSignedBtreeRootHash));
        } catch (IllegalStateException e) {
            return Futures.errored(e);
        }
        return target.setPointer(owner, writer, writerSignedBtreeRootHash);
    }

    @Override
    public CompletableFuture<Boolean> setPointers(PublicKeyHash owner, List<SignedPointerUpdate> updates) {
        try {
            for (SignedPointerUpdate u : updates)
                check(owner, u);
        } catch (IllegalStateException e) {
            return Futures.errored(e);
        }
        return target.setPointers(owner, updates);
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getPointer(PublicKeyHash owner, PublicKeyHash writer) {
        return target.getPointer(owner, writer);
    }

    @Override
    public MutablePointers clearCache() {
        return this;
    }
}
