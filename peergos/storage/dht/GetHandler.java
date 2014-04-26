package peergos.storage.dht;


public interface GetHandler extends PullHandler
{
    public byte[] getResult();
}
