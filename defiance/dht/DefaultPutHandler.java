package defiance.dht;

public class DefaultPutHandler extends AbstractRequestHandler implements PutHandler
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
        // upload file to target with a HTTP POST request


        setCompleted();
    }
}
