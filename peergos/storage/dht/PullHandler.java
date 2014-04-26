package peergos.storage.dht;


public interface PullHandler extends RequestHandler
{
    public void handleResult(GetOffer offer);
}
