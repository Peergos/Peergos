package peergos.shared.storage;

import jsinterop.annotations.JsConstructor;

public class MajorRateLimitException extends RuntimeException {

    @JsConstructor
    public MajorRateLimitException(String msg) {
        super(msg);
    }

}
