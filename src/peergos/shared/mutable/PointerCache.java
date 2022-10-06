package peergos.shared.mutable;

import peergos.shared.crypto.hash.*;

import java.util.*;
import java.util.concurrent.*;

public interface PointerCache {

    CompletableFuture<Boolean> put(PublicKeyHash owner, PublicKeyHash writer, byte[] writerSignedBtreeRootHash);

    CompletableFuture<Optional<byte[]>> get(PublicKeyHash owner, PublicKeyHash writer);
}
