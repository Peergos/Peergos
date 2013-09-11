package defiance.dht;


public interface GetHandler extends RequestHandler
{
    public void handleResult(GetOffer offer);

    public byte[] getResult();
}
