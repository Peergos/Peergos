package java.net;

import java.io.IOException;

public class ProtocolException extends IOException{
    public ProtocolException() {
    }

    public ProtocolException(String msg) {
        super(msg);
    }
}
