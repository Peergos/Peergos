package java.net;

import jsinterop.annotations.*;

public class ConnectException extends SocketException {

    public ConnectException() {
        this("");
    }

    @JsConstructor
    public ConnectException(String msg) {
        super(msg);
    }
}
