package java.net;

import java.io.IOException;

public class MalformedURLException extends IOException {

    public MalformedURLException() {
    }

    public MalformedURLException(String msg) {
        super(msg);
    }
}
