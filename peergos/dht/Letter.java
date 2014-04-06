package peergos.dht;

import java.net.InetAddress;

public class Letter
{
    public final Message m;
    public final InetAddress dest;
    public final int destPort;

    public Letter(Message m, InetAddress dest, int destPort)
    {
        this.m = m;
        this.dest = dest;
        this.destPort = destPort;
    }
}
