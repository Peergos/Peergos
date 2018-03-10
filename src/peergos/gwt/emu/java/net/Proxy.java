package java.net;

public class Proxy {
    public enum Type {DIRECT, HTTP, SOCKS}

    public Proxy(Type type, SocketAddress addr) {}
}