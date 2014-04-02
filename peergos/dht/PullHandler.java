package peergos.dht;


public interface PullHandler extends RequestHandler
{
    public void handleResult(GetOffer offer);
}
