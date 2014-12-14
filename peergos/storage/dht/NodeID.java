package peergos.storage.dht;

import java.io.*;
import java.net.*;
import java.util.*;

public class NodeID
{
    public final long id;
    public InetSocketAddress external;

    public NodeID(long id, InetSocketAddress local)
    {
        this.id = id;
        this.external = local;
    }

    public NodeID(InetSocketAddress external) throws IOException
    {
        this(generateID(), external);
    }

    public NodeID(DataInput in) throws IOException
    {
        id = in.readLong();
        int len = 16;
        if (in.readByte() == 4)
            len = 4;
        byte[] buf = new byte[len];
        in.readFully(buf);
        external = new InetSocketAddress(InetAddress.getByAddress(buf), in.readInt());
    }

    public String name()
    {
        return String.format("%08x", id);
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
        if (external.getAddress() instanceof Inet4Address)
            out.writeByte(4);
        else
            out.writeByte(6);
        out.write(external.getAddress().getAddress());
        out.writeInt(external.getPort());
    }

    public static NodeID newID(NodeID old)
    {
        return new NodeID(generateID(), old.external);
    }

    private static long generateID()
    {
        return new Random(System.currentTimeMillis()).nextLong();
    }

    public int hashCode() {
        return (int)id ^ (int)(id >> 32) ^ external.getPort() ^ external.getAddress().hashCode();
    }

    public boolean equals(Object o) {
        if (!(o instanceof NodeID))
            return false;
        NodeID other = (NodeID) o;
        if (id != other.id)
            return false;
        if (external.getPort() != other.external.getPort())
            return false;
        return external.getHostName().equals(other.external.getHostName());
    }
}
