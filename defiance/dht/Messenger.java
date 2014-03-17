package defiance.dht;

import java.io.IOException;
import java.net.InetAddress;
import java.util.logging.Logger;

public abstract class Messenger
{
    public abstract void sendMessage(Message m, InetAddress addr, int port) throws IOException;

    public abstract Message awaitMessage(int duration) throws IOException;

    public static Messenger getDefault(int port, Logger log) throws IOException
    {
        return new UDPMessenger(port, log);
    }
}
