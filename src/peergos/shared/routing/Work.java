package peergos.shared.routing;

import java.util.concurrent.*;

/** An asymmetric, memory-hard proof of work over a new key (remove-pki §4.5): expensive to find, cheap to check. */
public interface Work {

    CompletableFuture<Boolean> verify(byte[] subject, byte[] proof);
}
