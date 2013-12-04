package defiance.dht;


public class API
{
    private RoutingServer routing;

    public API(RoutingServer routing)
    {
        this.routing = routing;
    }

    // 256 bit key / 32 byte
    public PutHandler put(byte[] key, byte[] value)
    {
        assert(key.length == 32);
        PutHandler handler = new DefaultPutHandler(key, value);
        routing.sendPUT(key, value.length, handler);
        return handler;
    }

    public ContainsHandler contains(byte[] key)
    {
        ContainsHandler handler = new DefaultContainsHandler(key);
        routing.sendCONTAINS(key, handler);
        return handler;
    }

    public GetHandler get(byte[] key)
    {
        GetHandler res = new DefaultGetHandler(key);
        routing.sendGET(key, res);
        return res;
    }
}
