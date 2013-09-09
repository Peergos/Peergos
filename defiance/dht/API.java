package defiance.dht;

public class API
{
    private RoutingServer routing;

    public API(RoutingServer routing)
    {
        this.routing = routing;
    }


    // 256 bit key / 32 byte
    public void put(byte[] key, byte[] value, PutHandler handler)
    {
        assert(key.length <= 32);
        routing.sendPUT(key, value.length, handler);
    }

    public void contains(byte[] key)
    {

    }

    public void get(byte[] key)
    {

    }
}
