package peergos.shared.storage;

import jsinterop.annotations.*;

public class HttpFileNotFoundException extends RuntimeException {

    @JsConstructor
    public HttpFileNotFoundException() {
        super("Http 404: File not found exception!");
    }
}
