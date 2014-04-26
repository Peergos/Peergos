package peergos.storage.dht;


public interface PublicKeyPutHandler extends RequestHandler
{
    public void handleOffer(PublicKeyPutResult offer);

    public boolean getResult();
}
