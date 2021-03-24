package peergos.shared.storage;

import jsinterop.annotations.JsConstructor;

public class StorageQuotaExceededException extends RuntimeException {

    @JsConstructor
    public StorageQuotaExceededException(String msg) {
        super(msg);
    }
}
