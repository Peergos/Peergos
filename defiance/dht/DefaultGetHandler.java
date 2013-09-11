package defiance.dht;

public class DefaultGetHandler extends AbstractRequestHandler implements GetHandler
{
    final byte[] key;
    byte[] value;

    public DefaultGetHandler(byte[] key)
    {
        this.key = key;
    }

    @Override
    public void handleResult(GetOffer offer)
    {
        // download fragment using HTTP GET


        setCompleted();
    }

    @Override
    public byte[] getResult()
    {
        return value;
    }
}
