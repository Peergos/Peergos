package peergos.shared.login.mfa;

import java.util.concurrent.*;

public interface MultiFactorAuthSupplier {

    CompletableFuture<MultiFactorAuthResponse> authorise(MultiFactorAuthRequest req);
}
