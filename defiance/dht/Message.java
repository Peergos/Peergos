package defiance.dht;

import java.io.*;
import java.util.*;

public abstract class Message
{
    public static enum Type {JOIN}
    private static Type[] lookup = Type.values();
    private Type t;

    public Message(Type t)
    {
        this.t = t;
    }

    public void write(DataOutput out) throws IOException
    {
        out.writeByte((byte)t.ordinal());
    }

    public static Message read(DataInput in) throws IOException
    {
        int index = in.readByte() & 0xff;
        switch (lookup[index])
        {
            case JOIN:
                return new JOIN(in);
        }
        throw new IllegalStateException("Unknown Message type: " + index);
    }

    public static class JOIN extends Message
    {
        public NodeID target;
        public List<NodeID> hopNodes = new ArrayList();

        public JOIN(NodeID target)
        {
            super(Type.JOIN);
            this.target = target;
        }

        public JOIN(DataInput in) throws IOException
        {
            super(Type.JOIN);
            target = new NodeID(in);
            int n = in.readInt();
            for (int i=0; i < n; i++)
            {
                hopNodes.add(new NodeID(in));
            }
        }

        public void write(DataOutput out) throws IOException
        {
            super.write(out);
            target.write(out);
            out.writeInt(hopNodes.size());
            for (NodeID hop: hopNodes)
                hop.write(out);
        }
    }
}
