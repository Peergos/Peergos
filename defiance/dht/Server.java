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

    private SortedMap<Long, Node> leftNeighbours = new TreeMap();
    private SortedMap<Long, Node> rightNeighbours = new TreeMap();
    private SortedMap<Long, Node> friends = new TreeMap();

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
            while (true) // TODO make multi-threaded
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
        // add hop nodes to routing table/neighbours
        addNodes(m.getHops());

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

    private void addNodes(List<NodeID> hops)
    {
        for (NodeID n : hops)
            addNode(n);
    }

    private void addNode(NodeID n)
    {
        long toUs = us.d(n);
        if (toUs == 0)
            return;
        // maybe neighbours can be discovered purely through the ECHOs
//            if ((n.id < us.id) && (leftNeighbours.size() > 0) && (leftNeighbours.get(leftNeighbours.firstKey()).d(us) > toUs))
//            {
//                leftNeighbours.put(n.id, n);
//                leftNeighbours.remove(leftNeighbours.firstKey());
//            }
//            else if ((n.id > us.id) && (rightNeighbours.size() > 0) && (rightNeighbours.get(rightNeighbours.lastKey()).d(us) > toUs))
//            {
//                rightNeighbours.put(n.id, n);
//                rightNeighbours.remove(rightNeighbours.lastKey());
//            }
//            else // add to friends
        {
            if (!friends.containsKey(n.id))
                friends.put(n.id, new Node(n));
            else
            {
                Node existing = friends.get(n.id);
                if ((!existing.node.addr.equals(n.addr) || (existing.node.port != n.port)))
                {
                    if (!existing.isLost())
                        return; // ignore nodes trying to overtake current node address
                    else
                        friends.put(n.id, new Node(n));
                }
                existing.receivedContact();
            }
        }
    }

    private void escalateMessage(Message m)
    {
        if (m instanceof Message.JOIN)
        {
            NodeID joiner = ((Message.JOIN) m).target;
            if (joiner.id > us.id)
            {
                rightNeighbours.put(joiner.id, new Node(joiner));
                if (rightNeighbours.size() > MAX_NEIGHBOURS)
                    rightNeighbours.remove(rightNeighbours.lastKey());
            } else if (joiner.id < us.id)
            {
                leftNeighbours.put(joiner.id, new Node(joiner));
                if (leftNeighbours.size() > MAX_NEIGHBOURS)
                    leftNeighbours.remove(leftNeighbours.firstKey());
            } else
                return;

            // send ECHO message to joiner (1 attempt)
            sendECHO(joiner);
        } else if (m instanceof Message.ECHO)
        {
            int nright = 0;
            int nleft = 0;
            NodeID from = ((Message.ECHO) m).getHops().get(0);
            SortedMap<Long, Node> temp = new TreeMap();
            for (NodeID n : ((Message.ECHO) m).getNeighbours())
            {

                if (!leftNeighbours.containsKey(n.id) && !rightNeighbours.containsKey(n.id))
                {
                    Node fresh = new Node(n);
                    temp.put(n.id, fresh);
                }
            }
            temp.putAll(rightNeighbours);
            temp.putAll(leftNeighbours);
            if (temp.containsKey(from.id))
                temp.get(from.id).receivedContact();
            else
                temp.put(from.id, new Node(from));
            rightNeighbours.clear();
            leftNeighbours.clear();
            temp.remove(us.id);
            Set<Node> toSendECHO = new HashSet();

            // take up to MAX_NEIGHBOURS in each direction that are not Lost and send them an ECHO (if they haven't already been sent one)
            SortedMap<Long, Node> right = temp.tailMap(us.id + 1);
            while ((nright < MAX_NEIGHBOURS) && (!right.isEmpty()))
            {
                Node nextClosest = right.get(right.firstKey());
                right.remove(nextClosest.node.id);
                if (nextClosest.isLost())
                {
                    rightNeighbours.remove(nextClosest.node.id);
                    leftNeighbours.remove(nextClosest.node.id);
                    friends.remove(nextClosest.node.id);
                    continue;
                }
                if (nextClosest.isRecent())
                {
                    rightNeighbours.put(nextClosest.node.id, nextClosest);
                } else if (nextClosest.isWaiting())
                {
                    nextClosest.setTimedOut();
                    continue;
                } else // Good but not recent, send an ECHO
                {
                    rightNeighbours.put(nextClosest.node.id, nextClosest);
                    toSendECHO.add(nextClosest);
                }
                nright++;
            }
            // and the let neighbours..
            SortedMap<Long, Node> left = temp.headMap(us.id);
            while ((nleft < MAX_NEIGHBOURS) && (!left.isEmpty()))
            {
                Node nextClosest = left.get(left.lastKey());
                left.remove(nextClosest.node.id);
                if (nextClosest.isLost())
                {
                    rightNeighbours.remove(nextClosest.node.id);
                    leftNeighbours.remove(nextClosest.node.id);
                    friends.remove(nextClosest.node.id);
                    continue;
                }
                if (nextClosest.isRecent())
                {
                    leftNeighbours.put(nextClosest.node.id, nextClosest);
                } else if (nextClosest.isWaiting())
                {
                    nextClosest.setTimedOut();
                    continue;
                } else // Good but not recent, send an ECHO
                {
                    leftNeighbours.put(nextClosest.node.id, nextClosest);
                    toSendECHO.add(nextClosest);
                }
                nright++;
            }
            for (Node n: toSendECHO)
            {
                sendECHO(n.node);
            }
        }

        if (Message.LOG)

            printNeighboursAndFriends();

    }

    public void printNeighboursAndFriends()
    {
        System.out.println("Left Neighbours:");
        for (Node n : leftNeighbours.values())
            System.out.printf("%s:%d %s id=%d d=%d\n", n.node.addr.getHostAddress(), n.node.port, n.state.name(), n.node.id, n.node.d(us));
        System.out.println("Right Neighbours:");
        for (Node n : rightNeighbours.values())
            System.out.printf("%s:%d %s id=%d d=%d\n", n.node.addr.getHostAddress(), n.node.port, n.state.name(), n.node.id, n.node.d(us));
        System.out.println("Friends:");
        for (Node n : friends.values())
            System.out.printf("%s:%d %s id=%d d=%d\n", n.node.addr.getHostAddress(), n.node.port, n.state.name(), n.node.id, n.node.d(us));
        System.out.println();
    }

    private void sendECHO(NodeID target)
    {
        Message echo = new Message.ECHO(target, us, leftNeighbours.values(), rightNeighbours.values());
        try
        {
            sendMessage(echo, target.addr, target.port);
        } catch (IOException e)
        {
            e.printStackTrace();
        }
    }

    private NodeID getClosest(Message m)
    {
        long target = m.getTarget();
        NodeID next = us;
        long min = next.d(target);
        for (Node n : leftNeighbours.values())
        {
            if ((n.node.d(target) < min) && !n.isLost())
            {
                next = n.node;
                min = n.node.d(target);
            }
        }
        for (Node n : rightNeighbours.values())
        {
            if ((n.node.d(target) < min) && !n.isLost())
            {
                next = n.node;
                min = n.node.d(target);
            }
        }
        SortedMap lesser = friends.headMap(target);
        if (lesser.size() > 0)
        {
            NodeID less = friends.get(lesser.lastKey()).node;
            if (less.d(target) < min)
            {
                next = less;
                min = less.d(target);
            }
        }
        SortedMap greaterEq = friends.tailMap(target);
        if (greaterEq.size() > 0)
        {
            NodeID more = friends.get(greaterEq.firstKey()).node;
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
        if (Args.hasOption("help"))
        {
            Args.printOptions();
            System.exit(0);
        }
        int port = Args.getInt("port", 8080);
        new Server(port).start();
    }
}
