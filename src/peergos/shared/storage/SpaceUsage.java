package peergos.shared.storage;

import peergos.shared.crypto.hash.*;

import java.util.concurrent.*;

public interface SpaceUsage {

    CompletableFuture<Long> getUsage(PublicKeyHash owner);

    CompletableFuture<Long> getQuota(PublicKeyHash owner, byte[] signedTime);

}
