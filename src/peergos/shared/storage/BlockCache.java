package peergos.shared.storage;

import peergos.shared.io.ipfs.Cid;

import java.util.*;
import java.util.concurrent.*;

public interface BlockCache {

    CompletableFuture<Boolean> put(Cid hash, byte[] data);

    CompletableFuture<Optional<byte[]>> get(Cid hash);

    boolean hasBlock(Cid hash);

    CompletableFuture<Boolean> clear();

}
