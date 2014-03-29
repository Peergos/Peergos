package defiance.dht;

import defiance.crypto.UserPublicKey;
import defiance.util.*;
import defiance.util.Arrays;

import java.io.*;
import java.util.*;

public abstract class Message
{
    public static final boolean LOG = Args.hasOption("logMessages");
    public static final int KEY_BYTE_LENGTH = 32;

    public static enum Type
    {
        JOIN, ECHO, PUT, PUT_ACCEPT, GET, GET_RESULT, PUBLIC_KEY_PUT, PUBLIC_KEY_GET, PUBLIC_KEY_RESULT
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

    public String name()
    {
        return t.name();
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

    public long getOrigin()
    {
        return getHops().get(0).id;
    }

    public abstract long getTarget();

    public static Message read(DataInput in) throws IOException
    {
        int index = in.readByte() & 0xff;

        switch (lookup[index])
        {
            case JOIN:
                return new JOIN(in);
            case ECHO:
                return new ECHO(in);
            case PUT:
                return new PUT(in);
            case PUT_ACCEPT:
                return new PUT_ACCEPT(in);
            case GET:
                return new GET(in);
            case GET_RESULT:
                return new GET_RESULT(in);
            case PUBLIC_KEY_PUT:
                return new PUBLIC_KEY_PUT(in);
            case PUBLIC_KEY_GET:
                return new PUBLIC_KEY_GET(in);
            case PUBLIC_KEY_RESULT:
                return new PUBLIC_KEY_RESULT(in);
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

    public static class PUT extends Message
    {
        private final long target;
        private final byte[] key;
        private final int len;

        public PUT(NodeID us, byte[] key, int len)
        {
            super(Type.PUT);
            addNode(us);
            this.key = key;
            target = Arrays.getLong(key, 0);
            this.len = len;
        }

        public PUT(DataInput in) throws IOException
        {
            super(Type.PUT, in);
            key = new byte[KEY_BYTE_LENGTH];
            in.readFully(key);
            len = in.readInt();
            target = Arrays.getLong(key, 0);
        }

        public long getTarget()
        {
            return target;
        }

        public void write(DataOutput out) throws IOException
        {
            super.write(out);
            out.write(key);
            out.writeInt(len);
        }

        public byte[] getKey()
        {
            return key;
        }

        public int getSize()
        {
            return len;
        }
    }

    public static class PUT_ACCEPT extends Message
    {
        private final long target;
        private final byte[] key;
        private final int len;

        public PUT_ACCEPT(NodeID us, Message.PUT put)
        {
            super(Type.PUT_ACCEPT);
            addNode(us);
            this.key = put.getKey();
            target = put.getHops().get(0).id;
            this.len = put.getSize();
        }

        public PUT_ACCEPT(DataInput in) throws IOException
        {
            super(Type.PUT_ACCEPT, in);
            key = new byte[KEY_BYTE_LENGTH];
            in.readFully(key);
            len = in.readInt();
            target = in.readLong();
        }

        public long getTarget()
        {
            return target;
        }

        public void write(DataOutput out) throws IOException
        {
            super.write(out);
            out.write(key);
            out.writeInt(len);
            out.writeLong(target);
        }

        public byte[] getKey()
        {
            return key;
        }

        public int getSize()
        {
            return len;
        }
    }

    public static class GET extends Message
    {
        private final long target;
        private final byte[] key;

        public GET(NodeID us, byte[] key)
        {
            super(Type.GET);
            addNode(us);
            this.key = key;
            target = Arrays.getLong(key, 0);
        }

        public GET(NodeID us, byte[] key, long target)
        {
            super(Type.GET);
            addNode(us);
            this.key = key;
            this.target = target;
        }

        public GET(DataInput in) throws IOException
        {
            super(Type.GET, in);
            key = new byte[KEY_BYTE_LENGTH];
            in.readFully(key);
            target = Arrays.getLong(key, 0);
        }

        public long getTarget()
        {
            return target;
        }

        public void write(DataOutput out) throws IOException
        {
            super.write(out);
            out.write(key);
        }

        public byte[] getKey()
        {
            return key;
        }
    }

    public static class GET_RESULT extends Message
    {
        private final long target;
        private final byte[] key;
        private final int len;

        public GET_RESULT(NodeID us, Message.GET put, int len)
        {
            super(Type.GET_RESULT);
            addNode(us);
            this.key = put.getKey();
            target = put.getOrigin();
            this.len = len;
        }

        public GET_RESULT(DataInput in) throws IOException
        {
            super(Type.GET_RESULT, in);
            key = new byte[KEY_BYTE_LENGTH];
            in.readFully(key);
            len = in.readInt();
            target = in.readLong();
        }

        public long getTarget()
        {
            return target;
        }

        public void write(DataOutput out) throws IOException
        {
            super.write(out);
            out.write(key);
            out.writeInt(len);
            out.writeLong(target);
        }

        public byte[] getKey()
        {
            return key;
        }

        public int getSize()
        {
            return len;
        }
    }

    public static class PUBLIC_KEY_PUT extends Message
    {
        private final long target;
        private final int recursion;
        private final byte[] username;
        private final byte[] hashedUsername;
        private final byte[] publicKey;

        public PUBLIC_KEY_PUT(NodeID us, byte[] username, byte[] publicKey, int recursion)
        {
            super(Type.PUBLIC_KEY_PUT);
            assert(username.length < UserPublicKey.MAX_USERNAME_BYTES);
            addNode(us);
            this.username = username;
            this.publicKey = publicKey;
            this.recursion = recursion;
            hashedUsername = UserPublicKey.recursiveHash(username, recursion);
            target = Arrays.getLong(hashedUsername, 0);
        }

        public PUBLIC_KEY_PUT(DataInput in) throws IOException
        {
            super(Type.PUBLIC_KEY_PUT, in);
            username = new byte[in.readInt()];
            assert(username.length < UserPublicKey.MAX_USERNAME_BYTES);
            in.readFully(username);
            publicKey = new byte[in.readInt()];
            in.readFully(publicKey);
            recursion = in.readInt();
            hashedUsername = UserPublicKey.recursiveHash(username, recursion);
            target = Arrays.getLong(hashedUsername, 0);
        }

        public long getTarget()
        {
            return target;
        }

        public void write(DataOutput out) throws IOException
        {
            super.write(out);
            out.writeInt(username.length);
            out.write(username);
            out.writeInt(publicKey.length);
            out.write(publicKey);
            out.writeInt(recursion);
        }

        public byte[] getUsername()
        {
            return username;
        }

        public byte[] getHashedUsername()
        {
            return hashedUsername;
        }

        public int getRecursion()
        {
            return recursion;
        }

        public byte[] getPublicKey()
        {
            return publicKey;
        }
    }

    public static class PUBLIC_KEY_GET extends Message
    {
        private final long target;
        private final byte[] usernameHash;

        public PUBLIC_KEY_GET(NodeID us, byte[] usernameHash)
        {
            super(Type.PUBLIC_KEY_PUT);
            addNode(us);
            this.usernameHash = usernameHash;
            target = Arrays.getLong(usernameHash, 0);
        }

        public PUBLIC_KEY_GET(DataInput in) throws IOException
        {
            super(Type.PUBLIC_KEY_PUT, in);
            usernameHash = new byte[in.readInt()];
            in.readFully(usernameHash);
            target = Arrays.getLong(usernameHash, 0);
        }

        public long getTarget()
        {
            return target;
        }

        public void write(DataOutput out) throws IOException
        {
            super.write(out);
            out.writeInt(usernameHash.length);
            out.write(usernameHash);
        }

        public byte[] getUsernameHash()
        {
            return usernameHash;
        }
    }

    public static class PUBLIC_KEY_RESULT extends Message
    {
        private final long target;
        private final byte[] usernameHash;
        private final byte[] publicKey;
        private final boolean valid;

        public PUBLIC_KEY_RESULT(NodeID us, long origin, byte[] usernameHash, byte[] publicKey, boolean isValid)
        {
            super(Type.PUBLIC_KEY_RESULT);
            addNode(us);
            this.usernameHash = usernameHash;
            this.publicKey = publicKey;
            target = origin;
            valid = isValid;
        }

        public PUBLIC_KEY_RESULT(DataInput in) throws IOException
        {
            super(Type.PUBLIC_KEY_RESULT, in);
            usernameHash = new byte[in.readInt()];
            in.readFully(usernameHash);
            publicKey = new byte[in.readInt()];
            in.readFully(publicKey);
            valid = in.readBoolean();
            target = in.readLong();
        }

        public long getTarget()
        {
            return target;
        }

        public void write(DataOutput out) throws IOException
        {
            super.write(out);
            out.writeInt(usernameHash.length);
            out.write(usernameHash);
            out.writeInt(publicKey.length);
            out.write(publicKey);
            out.writeBoolean(valid);
            out.writeLong(target);
        }

        public boolean isValid()
        {
            return valid;
        }

        public byte[] getUsernameHash()
        {
            return usernameHash;
        }

        public byte[] getPublicKey()
        {
            return publicKey;
        }
    }
}
