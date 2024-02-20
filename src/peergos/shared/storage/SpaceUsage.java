package peergos.shared.storage;

import peergos.shared.crypto.hash.*;

import java.util.concurrent.*;

public interface SpaceUsage extends QuotaControl {

    CompletableFuture<Long> getUsage(PublicKeyHash owner, byte[] signedTime);

}
