package peergos.server.storage;

import peergos.shared.io.ipfs.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

public interface BlockBuffer {

    CompletableFuture<Boolean> put(Cid hash, byte[] data);

    CompletableFuture<Optional<byte[]>> get(Cid hash);

    boolean hasBlock(Cid hash);

    CompletableFuture<Boolean> delete(Cid hash);

    void applyToAll(Consumer<Cid> action);

}
