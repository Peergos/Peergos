package peergos.server.storage;

import peergos.shared.crypto.hash.PublicKeyHash;
import peergos.shared.io.ipfs.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

public interface BlockBuffer {

    CompletableFuture<Boolean> put(PublicKeyHash owner, Cid hash, byte[] data);

    CompletableFuture<Optional<byte[]>> get(PublicKeyHash owner, Cid hash);

    boolean hasBlock(PublicKeyHash owner, Cid hash);

    CompletableFuture<Boolean> delete(PublicKeyHash owner, Cid hash);

    void applyToAll(BiConsumer<PublicKeyHash, Cid> action);

}
