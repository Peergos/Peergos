package peergos.storage.dht;


public interface RequestHandler
{
    public void onStart();

    public void onError(Throwable e);

    public void onComplete();
}
