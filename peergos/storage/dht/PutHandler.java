package peergos.storage.dht;


public interface PutHandler extends RequestHandler
{
    public void handleOffer(PutOffer offer);

}
