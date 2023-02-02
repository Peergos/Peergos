package peergos.shared.storage;

import jsinterop.annotations.JsConstructor;

public class ConcurrentModificationException extends RuntimeException {

    @JsConstructor
    public ConcurrentModificationException(String msg) {
        super(msg);
    }
}
