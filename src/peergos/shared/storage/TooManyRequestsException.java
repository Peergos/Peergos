package peergos.shared.storage;

import jsinterop.annotations.JsConstructor;

/** An HTTP 429: the server refused the request without acting on it, so it is safe to retry. */
public class TooManyRequestsException extends RateLimitException {

    @JsConstructor
    public TooManyRequestsException(String msg) {
        super(msg);
    }
}
