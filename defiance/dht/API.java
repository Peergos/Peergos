package defiance.dht;


public class API
{
    private RoutingServer routing;

    public API(RoutingServer routing)
    {
        this.routing = routing;
    }

    // username + public key API
    public void createUser(byte[] username, byte[] publicKey, PublicKeyPutHandlerCallback onComplete, ErrorHandlerCallback onError)
    {
        PublicKeyPutHandler handler = new DefaultPublicKeyPutHandler(username, publicKey, onComplete, onError);
        routing.sendPublicKeyPUT(username, publicKey, 0, handler);
        // TODO use reliable core database
    }

    public void getPublicKey(byte[] username, PublicKeyGetHandlerCallback onComplete, ErrorHandlerCallback onError)
    {
        PublicKeyGetHandler handler = new DefaultPublicKeyGetHandler(username, onComplete, onError);
        routing.sendPublicKeyGET(username, handler);
        // TODO use reliable core database
    }

    public void createUser(byte[] username, byte[] publicKey, PublicKeyPutHandlerCallback onComplete)
    {
        createUser(username, publicKey, onComplete, getPrintErrorHandler());
    }

    public void getPublicKey(byte[] username, PublicKeyGetHandlerCallback onComplete)
    {
        getPublicKey(username, onComplete, getPrintErrorHandler());
    }

    // Fragment API
    // 256 bit key / 32 byte
    public void put(byte[] key, byte[] value, PutHandlerCallback onComplete, ErrorHandlerCallback onError)
    {
        assert(key.length == 32);
        PutHandler handler = new DefaultPutHandler(key, value, onComplete, onError, routing.getMessenger());
        routing.sendPUT(key, value.length, handler);
    }

    public void contains(byte[] key, ContainsHandlerCallback onComplete, ErrorHandlerCallback onError)
    {
        ContainsHandler handler = new DefaultContainsHandler(key, onComplete, onError);
        routing.sendCONTAINS(key, handler);
    }

    public void get(byte[] key, GetHandlerCallback onComplete, ErrorHandlerCallback onError)
    {
        GetHandler res = new DefaultGetHandler(key, onComplete, onError, routing.getMessenger());
        routing.sendGET(key, res);
    }

    public void put(byte[] key, byte[] value, PutHandlerCallback onComplete)
    {
        put(key, value, onComplete, getPrintErrorHandler());
    }

    public void contains(byte[] key, ContainsHandlerCallback onComplete)
    {
        contains(key, onComplete, getPrintErrorHandler());
    }

    public void get(byte[] key, GetHandlerCallback onComplete)
    {
        get(key, onComplete, getPrintErrorHandler());
    }

    private ErrorHandlerCallback getPrintErrorHandler()
    {
        return new ErrorHandlerCallback() {
            @Override
            public void callback(Throwable e) {
                e.printStackTrace();
            }
        };
    }
}
