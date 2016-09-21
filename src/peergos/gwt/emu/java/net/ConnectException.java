package java.net;

public class ConnectException extends SocketException{

    public ConnectException() {
    }

    public ConnectException(String msg) {
        super(msg);
    }
}
