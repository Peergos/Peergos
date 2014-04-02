package peergos.dht;


public interface PublicKeyGetHandler extends RequestHandler
{
    public void handleResult(PublicKeyGetResult offer);

    public boolean isValid();

    public byte[] getResult();
}
