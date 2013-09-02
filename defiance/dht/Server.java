package defiance.dht;

import defiance.util.*;

import java.io.*;
import java.net.*;
import java.util.*;

public class Server extends Thread
{
    public static final int MAX_NEIGHBOURS = 10;
    public static final int MAX_UDP_PACKET_SIZE = 65507;
    public static final int MAX_PACKET_SIZE = MAX_UDP_PACKET_SIZE; // TODO: decrease this once protocol is finalized

    public static enum State
    {
        JOINING, READY;
    }

    private State state = State.JOINING;
    private DatagramSocket socket;
    private NodeID us;
    private List<NodeID> leftNeighbours = new ArrayList();
    private List<NodeID> rightNeighbours = new ArrayList();
    private SortedMap<Long, NodeID> friends = new TreeMap();

    public Server(int port) throws IOException
    {
        socket = new DatagramSocket(port);
        us = new NodeID();
    }

    public void run()
    {
        byte[] buf = new byte[MAX_PACKET_SIZE];
        // connect to network
        if (!Args.hasOption("firstNode"))
            while (true)
            {
                try
                {
                    int port = Args.getInt("contactPort", 8080);
                    InetAddress entry = InetAddress.getByName(Args.getParameter("contactIP"));

                    // send JOIN message to ourselves via contact point
                    Message join = new Message.JOIN(us);
                    sendMessage(join, entry, port);

                    // wait for ECHO from nearest neighbour, otherwise retry with new NodeID
                    socket.setSoTimeout(1000 * 60);
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    socket.receive(packet);
                    Message m = Message.read(new DataInputStream(new ByteArrayInputStream(packet.getData())));
                    actOnMessage(m);
                    socket.setSoTimeout(0);
                    break;
                } catch (SocketTimeoutException s)
                {
                    us = NodeID.newID(us);
                    continue;
                } catch (IOException e)
                {
                    e.printStackTrace();
                    return;
                }
            }
        state = State.READY;


        while (true)
        {
            try
            {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                socket.receive(packet);
                Message m = Message.read(new DataInputStream(new ByteArrayInputStream(packet.getData())));
                actOnMessage(m);

                InetAddress address = packet.getAddress();
                int port = packet.getPort();
                DatagramPacket response = new DatagramPacket(buf, buf.length, address, port);
                socket.send(response);
            } catch (IOException e)
            {
                e.printStackTrace();
            }
        }
    }

    private void sendMessage(Message m, InetAddress addr, int port) throws IOException
    {
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        m.write(new DataOutputStream(bout));
        DatagramPacket response = new DatagramPacket(bout.toByteArray(), bout.size(), addr, port);
        socket.send(response);
    }

    private void actOnMessage(Message m)
    {
        NodeID next = getClosest(m);
        if (next != us)
        {
            m.addNode(us);
            try
            {
                sendMessage(m, next.addr, next.port);
            } catch (IOException e)
            {
                // should remove node from contacts
                e.printStackTrace();
            }
        } else
            escalateMessage(m);
    }

    private void escalateMessage(Message m)
    {
        if (m instanceof Message.JOIN)
        {
            NodeID joiner = ((Message.JOIN)m).target;
            if (joiner.id > us.id)
            {
                rightNeighbours.add(0, joiner);
                if (rightNeighbours.size() > MAX_NEIGHBOURS)
                    rightNeighbours.remove(rightNeighbours.size()-1);
            }
            else
            {
                leftNeighbours.add(0, joiner);
                if (leftNeighbours.size() > MAX_NEIGHBOURS)
                    leftNeighbours.remove(leftNeighbours.size()-1);
            }

            // send ECHO message to joiner
            
        }
    }

    private NodeID getClosest(Message m)
    {
        long target = m.getTarget();
        NodeID next = us;
        long min = next.d(target);
        for (NodeID n : leftNeighbours)
        {
            if (n.d(target) < min)
            {
                next = n;
                min = n.d(target);
            }
        }
        for (NodeID n : rightNeighbours)
        {
            if (n.d(target) < min)
            {
                next = n;
                min = n.d(target);
            }
        }
        SortedMap lesser = friends.headMap(target);
        if (lesser.size() > 0)
        {
            NodeID less = friends.get(lesser.lastKey());
            if (less.d(target) < min)
            {
                next = less;
                min = less.d(target);
            }
        }
        SortedMap greaterEq = friends.tailMap(target);
        if (greaterEq.size() > 0)
        {
            NodeID more = friends.get(greaterEq.firstKey());
            if (more.d(target) < min)
            {
                next = more;
                min = more.d(target);
            }
        }
        return next;
    }

    public static void main(String[] args) throws IOException
    {
        Args.parse(args);
        int port = Args.getInt("port", 8080);
        new Server(port).start();
    }
}
