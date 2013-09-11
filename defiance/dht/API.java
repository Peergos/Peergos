package defiance.dht;


public class API
{
    private RoutingServer routing;

    public API(RoutingServer routing)
    {
        this.routing = routing;
    }

    // 256 bit key / 32 byte
    public void put(byte[] key, byte[] value)
    {
        assert(key.length == 32);
        PutHandler handler = new DefaultPutHandler(key, value);
        routing.sendPUT(key, value.length, handler);
    }

    public void contains(byte[] key)
    {
        routing.sendGET(key, new DefaultContainsHandler(key));
    }

    public GetHandler get(byte[] key)
    {
        GetHandler res = new DefaultGetHandler(key);
        routing.sendGET(key, res);
        return res;
    }
}
