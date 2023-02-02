package peergos.shared.storage;

import jsinterop.annotations.JsConstructor;

public class CasException extends RuntimeException {

    @JsConstructor
    public CasException(String msg) {
        super(msg);
    }

    public CasException(Object actualExisting, Object claimedExisting) {
        this("CAS exception updating cryptree node. existing: " + actualExisting + ", claimed: " + claimedExisting);
    }
}
