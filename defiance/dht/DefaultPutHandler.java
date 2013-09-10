package defiance.dht;

public class DefaultPutHandler implements PutHandler
{
    byte[] key, value;

    public DefaultPutHandler(byte[] key, byte[] value)
    {
        this.key = key;
        this.value= value;
    }

    @Override
    public void handleOffer(PutOffer offer)
    {

    }
}
