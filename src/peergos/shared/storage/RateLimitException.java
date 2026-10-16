package peergos.shared.storage;

import jsinterop.annotations.*;

public class RateLimitException extends RuntimeException {

    @JsConstructor
    public RateLimitException() {
        super();
    }
}
