package peergos.user;

import peergos.util.*;

import java.util.concurrent.*;

public interface DHTUserAPI
{
    CompletableFuture<Boolean> put(byte[] key, byte[] value, final byte[] ownerKey, final byte[] sharingKey, final byte[] mapKey, final byte[] proof);

    CompletableFuture<Boolean> contains(byte[] key);

    CompletableFuture<ByteArrayWrapper> get(byte[] key);

    void shutdown();
}
