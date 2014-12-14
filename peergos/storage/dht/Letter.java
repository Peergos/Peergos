package peergos.storage.dht;

import java.net.InetSocketAddress;

public class Letter
{
    public final Message m;
    public final InetSocketAddress dest;

    public Letter(Message m, InetSocketAddress dest)
    {
        this.m = m;
        this.dest = dest;
    }
}
