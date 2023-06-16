package peergos.shared.login.mfa;

import jsinterop.annotations.JsMethod;
import jsinterop.annotations.JsType;

import java.util.concurrent.*;
@JsType
public interface MultiFactorAuthSupplier {

    CompletableFuture<MultiFactorAuthResponse> authorise(MultiFactorAuthRequest req);
}
