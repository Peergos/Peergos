package defiance.dht;


public class API
{
    private RoutingServer routing;

    public API(RoutingServer routing)
    {
        this.routing = routing;
    }

    // 256 bit key / 32 byte
    public void put(byte[] key, byte[] value, PutHandlerCallback onComplete)
    {
        assert(key.length == 32);
        PutHandler handler = new DefaultPutHandler(key, value, onComplete);
        routing.sendPUT(key, value.length, handler);
    }

    public void contains(byte[] key, ContainsHandlerCallback onComplete)
    {
        ContainsHandler handler = new DefaultContainsHandler(key, onComplete);
        routing.sendCONTAINS(key, handler);
    }

    public void get(byte[] key, GetHandlerCallback onComplete)
    {
        GetHandler res = new DefaultGetHandler(key, onComplete);
        routing.sendGET(key, res);
    }
}
