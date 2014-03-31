package defiance.dht;

import defiance.net.HTTPSMessenger;
import defiance.storage.Storage;

import java.io.IOException;
import java.net.InetAddress;
import java.util.logging.Logger;

public abstract class Messenger
{
    public Messenger() {}

    public abstract boolean join(InetAddress addr, int port) throws IOException;

    public abstract void sendMessage(Message m, InetAddress addr, int port) throws IOException;

    public abstract Message awaitMessage(int duration) throws IOException, InterruptedException;

    public abstract byte[] getFragment(InetAddress addr, int port, String key) throws IOException;

    public abstract void putFragment(InetAddress addr, int port, String key, byte[] value) throws IOException;

    public static Messenger getDefault(int port, Storage fragments, Storage keys, Logger log) throws IOException
    {
        return new HTTPSMessenger(port, fragments, keys, log);
    }
}
