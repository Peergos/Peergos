package peergos.dht;

import akka.actor.ActorRef;
import peergos.net.HTTPSMessenger;
import peergos.storage.Storage;

import java.io.IOException;
import java.net.InetAddress;
import java.util.logging.Logger;

public interface Messenger
{
    public abstract boolean init(ActorRef router) throws IOException;

    public abstract void sendMessage(Message m, InetAddress addr, int port) throws IOException;

    public abstract byte[] getFragment(InetAddress addr, int port, String key) throws IOException;

    public abstract void putFragment(InetAddress addr, int port, String key, byte[] value) throws IOException;

    public static Messenger getDefault(int port, Storage fragments, Logger log) throws IOException
    {
        return new HTTPSMessenger(port, fragments, log);
    }

    public static class JOIN
    {
        public final InetAddress addr;
        public final int port;

        public JOIN(InetAddress addr, int port)
        {
            this.addr = addr;
            this.port = port;
        }
    }

    public static class JOINED {}
    public static class JOINERROR {}

    public static class INITIALIZE {}
    public static class INITIALIZED {}
    public static class INITERROR {}
}
