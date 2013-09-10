package defiance.dht;

import java.util.logging.*;

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
        assert(key.length <= 32);
        PutHandler handler = new DefaultPutHandler(key, value);
        routing.LOGGER.log(Level.ALL, "API.put");
        routing.sendPUT(key, value.length, handler);
    }

    public void contains(byte[] key)
    {

    }

    public void get(byte[] key)
    {

    }
}
