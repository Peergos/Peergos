package defiance.dht;

import java.io.*;

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
        public JOIN(DataInput in) throws IOException
        {
            super(Type.JOIN);

        }
    }
}
