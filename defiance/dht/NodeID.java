package defiance.dht;

import defiance.util.*;

import java.io.*;
import java.net.*;
import java.util.*;

public class NodeID
{
    public final long id;
    public InetAddress addr;
    public int port;

    public NodeID(long id, InetAddress addr, int port)
    {
        this.id = id;
        this.addr = addr;
        this.port = port;
    }

    public NodeID() throws IOException
    {
        this(generateID(), getMyPublicAddress(), Args.getInt("port", 8080));
    }

    public NodeID(DataInput in) throws IOException
    {
        id = in.readLong();
        int len = 16;
        if (in.readByte() == 4)
            len = 4;
        byte[] buf = new byte[len];
        in.readFully(buf);
        addr = InetAddress.getByAddress(buf);
        port = in.readInt();
    }

    public boolean greaterThan(NodeID other)
    {
        return id > other.id;
    }

    public long d(NodeID other)
    {
        return d(other.id);
    }

    public long d(long other)
    {
        return Math.abs(other - id);
    }

    public void write(DataOutput out) throws IOException
    {
        out.writeLong(id);
        if (addr instanceof Inet4Address)
            out.writeByte(4);
        else
            out.writeByte(6);
        out.write(addr.getAddress());
        out.writeInt(port);
    }

    public static NodeID newID(NodeID old)
    {
        return new NodeID(generateID(), old.addr, old.port);
    }

    private static InetAddress getMyPublicAddress() throws IOException
    {
        // try to find our public IP address
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces.hasMoreElements()){
            NetworkInterface current = interfaces.nextElement();
            if (!current.isUp() || current.isLoopback() || current.isVirtual()) continue;
            Enumeration<InetAddress> addresses = current.getInetAddresses();
            while (addresses.hasMoreElements()){
                InetAddress current_addr = addresses.nextElement();
                if (current_addr.isLoopbackAddress()) continue;
                //System.out.println(current_addr.getHostAddress());
                return current_addr;
            }
        }
       throw new IOException("Is server connected to the internet?");
    }

    private static long generateID()
    {
        return new Random(System.currentTimeMillis()).nextLong();
    }
}
