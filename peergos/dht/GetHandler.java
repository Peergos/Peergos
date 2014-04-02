package peergos.dht;


public interface GetHandler extends PullHandler
{
    public byte[] getResult();
}
