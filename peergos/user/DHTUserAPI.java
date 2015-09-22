package peergos.user;

import peergos.util.*;

import java.util.concurrent.*;

public abstract class DHTUserAPI
{
    public abstract CompletableFuture<Boolean> put(byte[] key, byte[] value, final byte[] ownerKey, final byte[] sharingKey, final byte[] mapKey, final byte[] proof);

    public abstract CompletableFuture<Boolean> contains(byte[] key);

    public abstract CompletableFuture<ByteArrayWrapper> get(byte[] key);

    public abstract void shutdown();
}
