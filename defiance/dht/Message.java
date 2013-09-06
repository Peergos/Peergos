package defiance.dht;

import defiance.util.*;

import java.io.*;
import java.util.*;

public abstract class Message
{
    public static final boolean LOG = Args.hasOption("logMessages");

    public static enum Type
    {
        JOIN, ECHO
    }

    private static Type[] lookup = Type.values();
    private Type t;
    private List<NodeID> visited = new ArrayList(); // last element is previous node

    public Message(Type t)
    {
        this.t = t;
    }

    public Message(Type t, DataInput in) throws IOException
    {
        this.t = t;
        int n = in.readInt();
        for (int i = 0; i < n; i++)
        {
            visited.add(new NodeID(in));
        }
    }

    public void write(DataOutput out) throws IOException
    {
        out.writeByte((byte) t.ordinal());
        out.writeInt(visited.size());
        for (NodeID hop : visited)
            hop.write(out);
    }

    public void addNode(NodeID n)
    {
        visited.add(n);
    }

    public List<NodeID> getHops()
    {
        return visited;
    }

    public abstract long getTarget();

    public static Message read(DataInput in) throws IOException
    {
        int index = in.readByte() & 0xff;
        if (LOG)
            System.out.printf("Received %s\n", lookup[index].name());
        switch (lookup[index])
        {
            case JOIN:
                return new JOIN(in);
            case ECHO:
                return new ECHO(in);
        }
        throw new IllegalStateException("Unknown Message type: " + index);
    }

    public static class JOIN extends Message
    {
        public NodeID target;

        public JOIN(NodeID target)
        {
            super(Type.JOIN);
            this.target = target;
        }

        public JOIN(DataInput in) throws IOException
        {
            super(Type.JOIN, in);
            target = new NodeID(in);
        }

        public long getTarget()
        {
            return target.id;
        }

        public void write(DataOutput out) throws IOException
        {
            super.write(out);
            target.write(out);
        }
    }

    public static class ECHO extends Message
    {
        private NodeID target;
        private Set<NodeID> neighbours = new HashSet();

        public ECHO(NodeID target, NodeID us, Collection<Node> leftN, Collection<Node> rightN)
        {
            super(Type.ECHO);
            this.target = target;
            for (Node n: leftN)
                neighbours.add(n.node);
            for (Node n: rightN)
            neighbours.add(n.node);
            addNode(us);
        }

        public ECHO(DataInput in) throws IOException
        {
            super(Type.ECHO, in);
            target = new NodeID(in);
            int n = in.readInt();
            for (int i=0; i < n; i++)
                neighbours.add(new NodeID(in));
        }

        public long getTarget()
        {
            return target.id;
        }

        public Set<NodeID> getNeighbours()
        {
            return neighbours;
        }

        public void write(DataOutput out) throws IOException
        {
            super.write(out);
            target.write(out);
            out.writeInt(neighbours.size());
            for (NodeID n: neighbours)
                n.write(out);
        }
    }
}
