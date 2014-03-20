package defiance.dht;

import defiance.net.HTTPSMessenger;
import defiance.net.UDPMessenger;
import defiance.storage.Storage;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.util.logging.Logger;

public abstract class Messenger
{
    public Messenger() {}

    public abstract void join(InetAddress addr, int port) throws IOException;

    public abstract void sendMessage(Message m, InetAddress addr, int port) throws IOException;

    public abstract Message awaitMessage(int duration) throws IOException, InterruptedException;

    public static Messenger getDefault(int port, Storage keyStorage, Logger log) throws IOException
    {
        return new HTTPSMessenger(port, keyStorage, log);
//        return new UDPMessenger(port, log);
    }
}
