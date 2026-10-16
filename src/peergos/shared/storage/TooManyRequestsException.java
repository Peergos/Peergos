package peergos.shared.storage;

import jsinterop.annotations.JsConstructor;

/** An HTTP 429: the server refused the request without acting on it, so it is safe to retry. */
public class TooManyRequestsException extends RateLimitException {
    // GWT requires a JsConstructor to delegate to its parent's, which takes no message
    private final String msg;

    @JsConstructor
    public TooManyRequestsException(String msg) {
        super();
        this.msg = msg;
    }

    @Override
    public String getMessage() {
        return msg;
    }
}
