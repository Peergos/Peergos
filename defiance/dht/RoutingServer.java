package defiance.dht;

import defiance.crypto.PublicKey;
import defiance.util.*;
import defiance.util.Arrays;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;

public class RoutingServer extends Thread
{
    public static final int MAX_NEIGHBOURS = 2;
    public static final int MAX_UDP_PACKET_SIZE = 65507;
    public static final int MAX_PACKET_SIZE = MAX_UDP_PACKET_SIZE; // TODO: decrease this once protocol is finalized
    public static final long MAX_STORAGE_SIZE = 1024 * 1024 * 1024L;
    private static final String DATA_DIR = "data/";
    public static final long MAX_KEY_INFO_STORAGE_SIZE = 100 * 1024 * 1024L;
    private static final String KEY_DATA_DIR = "keys/";

    private DatagramSocket socket;
    private NodeID us;

    private SortedMap<Long, Node> leftNeighbours = new TreeMap();
    private SortedMap<Long, Node> rightNeighbours = new TreeMap();
    private SortedMap<Long, Node> friends = new TreeMap();
    private final Storage storage;
    private final Storage publicKeyStorage;
    public Logger LOGGER;
    private final Map<ByteArrayWrapper, PutHandler> pendingPuts = new ConcurrentHashMap();
    private final Map<ByteArrayWrapper, PullHandler> pendingGets = new ConcurrentHashMap();
    private final Map<ByteArrayWrapper, PublicKeyPutHandler> pendingPublicKeyPuts = new ConcurrentHashMap();
    private final Map<ByteArrayWrapper, PublicKeyGetHandler> pendingPublicKeyGets = new ConcurrentHashMap();
    private final Random random = new Random(System.currentTimeMillis());
    private final Executor complexHandlers = Executors.newFixedThreadPool(10);

    public RoutingServer(int port) throws IOException
    {
        new File("log/").mkdir();
        socket = new DatagramSocket(port);
        us = new NodeID();
        String name = us.id + ":" + us.port;
        setName("RoutingServer port: "+us.port);
        LOGGER = Logger.getLogger(name);
        Handler handler = new FileHandler("log/" + name + ".log", 10 * 1024 * 1024, 7);
        LOGGER.addHandler(handler);
        LOGGER.setLevel(Level.ALL);
        storage = new Storage(new File(DATA_DIR), MAX_STORAGE_SIZE);
        publicKeyStorage = new Storage(new File(KEY_DATA_DIR), MAX_KEY_INFO_STORAGE_SIZE);
        StorageServer.createAndStart(port, new File(DATA_DIR), storage, new File(KEY_DATA_DIR), publicKeyStorage);
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
                    String name = us.id + ":" + us.port;
                    LOGGER = Logger.getLogger(name);
                    try
                    {
                        Handler handler = new FileHandler("log/" + name + ".log", 10 * 1024 * 1024, 7);
                        LOGGER.addHandler(handler);
                        LOGGER.setLevel(Level.ALL);
                    } catch (IOException e)
                    {
                        System.out.println("Couldn't initialise logging to file.");
                    }
                    continue;
                } catch (IOException e)
                {
                    e.printStackTrace();
                    return;
                }
            }
        // Ready to receive traffic
        // TODO serialise our ID

        while (true)
        {
            try
            {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                socket.setSoTimeout((int) Node.NEIGHBOUR_TIMEOUT);
                socket.receive(packet);
                Message m = Message.read(new DataInputStream(new ByteArrayInputStream(packet.getData())));
                actOnMessage(m);
            } catch (SocketTimeoutException s)
            {
                // send ECHO to two random neighbours
                if (rightNeighbours.size() != 0)
                {
                    int count = 0;
                    int max = new Random().nextInt(rightNeighbours.size());
                    for (Node n : rightNeighbours.values())
                    {
                        count++;
                        if (count == max)
                        {
                            if (!n.wasRecentlySeen())
                                sendECHO(n.node);
                            break;
                        }
                    }
                }
                if (leftNeighbours.size() != 0)
                {
                    int count = 0;
                    int max = new Random().nextInt(leftNeighbours.size());
                    for (Node n : leftNeighbours.values())
                    {
                        count++;
                        if (count == max)
                        {
                            if (!n.wasRecentlySeen())
                                sendECHO(n.node);
                            break;
                        }
                    }
                }
            } catch (IOException e)
            {
                e.printStackTrace();
            }
        }
    }

    private void sendMessage(Message m, InetAddress addr, int port) throws IOException
    {
        if (Message.LOG)
            LOGGER.log(Level.ALL, String.format("Sent %s with target %d to %s:%d\n", m.name(), m.getTarget(), addr, port));
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        m.write(new DataOutputStream(bout));
        DatagramPacket response = new DatagramPacket(bout.toByteArray(), bout.size(), addr, port);
        socket.send(response);
    }

    private void actOnMessage(Message m)
    {
        if (Message.LOG)
        {
            if (m.getHops().size() > 0)
                LOGGER.log(Level.ALL, String.format("Received %s from %s:%d with target %d\n", m.name(), m.getHops().get(0).addr, m.getHops().get(0).port, m.getTarget()));
            else
                LOGGER.log(Level.ALL, String.format("Received %s\n", m.name()));
        }
        // add hop nodes to routing table/neighbours
        addNodes(m.getHops());

        forwardMessage(m);
    }

    private void forwardMessage(Message m)
    {
        NodeID next = getClosest(m);
        if (next != us)
        {
            m.addNode(getRandomNeighbour());
            try
            {
                sendMessage(m, next.addr, next.port);
            } catch (IOException e)
            {
                // should remove node from contacts
                e.printStackTrace();
            }
        } else
            escalateMessage(m); //bypass network in this unlikely case
    }

    private NodeID getRandomNeighbour()
    {
        List<Node> t = new ArrayList();
        t.addAll(leftNeighbours.values());
        t.addAll(rightNeighbours.values());
        t.add(new Node(us));
        return t.get(random.nextInt(t.size())).node;
    }

    private Node getNode(NodeID n)
    {
        if (leftNeighbours.containsKey(n.id))
            return leftNeighbours.get(n.id);
        if (rightNeighbours.containsKey(n.id))
            return rightNeighbours.get(n.id);
        if (friends.containsKey(n.id))
            return friends.get(n.id);
        return new Node(n);
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
        if (!friends.containsKey(n.id))
        {
            friends.put(n.id, getNode(n));
        } else
        {
            Node existing = friends.get(n.id);
            if ((!existing.node.addr.equals(n.addr) || (existing.node.port != n.port)))
            {
                if (!existing.isLost())
                    return; // ignore nodes trying to overtake current node address
                else
                    friends.put(n.id, getNode(n));
            }
            existing.receivedContact();
        }
    }

    private synchronized void escalateMessage(Message m)
    {
        if (Message.LOG)
            LOGGER.log(Level.ALL, String.format("Escalated to API %s\n", m.name()));
        if (m instanceof Message.JOIN)
        {
            // TODO: stop joining attacks
            // where a new node requests an ID which is for an already published fragment, which is stored on another node
            // thus receiving all future requests for that fragment
            // randomise the target ID on the first 3 hops in the network

            NodeID joiner = ((Message.JOIN) m).target;
            if (joiner.id > us.id)
            {
                rightNeighbours.put(joiner.id, getNode(joiner));
                if (rightNeighbours.size() > MAX_NEIGHBOURS)
                    rightNeighbours.remove(rightNeighbours.lastKey());
            } else if (joiner.id < us.id)
            {
                leftNeighbours.put(joiner.id, getNode(joiner));
                if (leftNeighbours.size() > MAX_NEIGHBOURS)
                    leftNeighbours.remove(leftNeighbours.firstKey());
            } else
                return;

            // send ECHO message to joiner (1 attempt)
            sendECHO(joiner);
        } else if (m instanceof Message.ECHO)
        {
            NodeID from = m.getHops().get(0);
            SortedMap<Long, Node> temp = new TreeMap();
            Set<Node> toSendECHO = new HashSet();
            for (NodeID n : ((Message.ECHO) m).getNeighbours())
            {
                if (!leftNeighbours.containsKey(n.id) && !rightNeighbours.containsKey(n.id))
                {
                    Node fresh = getNode(n);
                    temp.put(n.id, fresh);
                }
            }
            temp.putAll(rightNeighbours);
            temp.putAll(leftNeighbours);
            if (temp.containsKey(from.id))
                temp.get(from.id).receivedContact();
            else
            {
                Node fresh = getNode(from);
                temp.put(from.id, fresh);
            }
            rightNeighbours.clear();
            leftNeighbours.clear();
            temp.remove(us.id);


            // take up tisRecent() && !nextClosest.isWaiting()o MAX_NEIGHBOURS in each direction that are not Lost and send them an ECHO (if they haven't already been sent one)
            fillRight(temp.tailMap(us.id + 1), toSendECHO);
            // check wrap around
            if (rightNeighbours.size() < MAX_NEIGHBOURS)
                fillRight(temp.headMap(us.id), toSendECHO);
            if (rightNeighbours.size() < MAX_NEIGHBOURS)
                fillRight(friends.tailMap(us.id + 1), toSendECHO);
            if (rightNeighbours.size() < MAX_NEIGHBOURS)
                fillRight(friends.headMap(us.id), toSendECHO);

            fillLeft(temp.headMap(us.id), toSendECHO);
            if (leftNeighbours.size() < MAX_NEIGHBOURS)
                fillLeft(temp.tailMap(us.id + 1), toSendECHO);
            if (leftNeighbours.size() < MAX_NEIGHBOURS)
                fillLeft(friends.headMap(us.id), toSendECHO);
            if (leftNeighbours.size() < MAX_NEIGHBOURS)
                fillLeft(friends.tailMap(us.id + 1), toSendECHO);

            for (Node n : toSendECHO)
            {
                sendECHO(n.node);
            }
        } else if (m instanceof Message.PUT)
        {
            if (storage.accept(new ByteArrayWrapper(((Message.PUT) m).getKey()), ((Message.PUT) m).getSize()))
            {
                // send PUT accept message
                Message accept = new Message.PUT_ACCEPT(us, (Message.PUT) m);
                forwardMessage(accept);
            } else
            {
                // DO NOT reply
            }
        } else if (m instanceof Message.PUT_ACCEPT)
        {
            // initiate file transfer over tcp
            NodeID target = m.getHops().get(0);
            ByteArrayWrapper key = new ByteArrayWrapper(((Message.PUT_ACCEPT) m).getKey());
            if (pendingPuts.containsKey(key))
            {
                LOGGER.log(Level.ALL, "handling PUT_ACCEPT");
                pendingPuts.get(key).handleOffer(new PutOffer(target));
                pendingPuts.remove(key);
            }
        } else if (m instanceof Message.GET)
        {
            ByteArrayWrapper key = new ByteArrayWrapper(((Message.GET) m).getKey());
            if (storage.contains(key))
            {
                Message res = new Message.GET_RESULT(us, (Message.GET) m, storage.sizeOf(key));
                forwardMessage(res);
            } else
            {
                // forward to two neighbours as they might have it (if we were the original target of the request
                long keyLong = Arrays.getLong(((Message.GET) m).getKey(), 0);
                if (leftNeighbours.size() != 0)
                {
                    Node left = leftNeighbours.get(leftNeighbours.lastKey());
                    if (Math.abs(keyLong - us.id) < left.node.d(keyLong))
                    {
                        Message newput = new Message.GET(us, ((Message.GET) m).getKey(), left.node.id);
                        forwardMessage(newput);
                    }
                }
                if (rightNeighbours.size() != 0)
                {
                    Node right = rightNeighbours.get(rightNeighbours.firstKey());
                    if (Math.abs(keyLong - us.id) < right.node.d(keyLong))
                    {
                        Message newput = new Message.GET(us, ((Message.GET) m).getKey(), right.node.id);
                        forwardMessage(newput);
                    }
                }
            }
        } else if (m instanceof Message.GET_RESULT)
        {
            ByteArrayWrapper key = new ByteArrayWrapper(((Message.GET_RESULT) m).getKey());
            if (pendingGets.containsKey(key))
            {
                pendingGets.get(key).handleResult(new GetOffer(m.getHops().get(0), ((Message.GET_RESULT) m).getSize()));
                pendingGets.remove(key);
            }
            else
                LOGGER.log(Level.ALL, "Couldn't find GET_RESULT handler for " + Arrays.bytesToHex(key.data));
        } else if (m instanceof Message.PUBLIC_KEY_PUT)
        {
            LOGGER.log(Level.ALL, "received Public_Key_Put " + m.toString());
            complexHandlers.execute(new PublicKeyPutManager((Message.PUBLIC_KEY_PUT)m));
        } else if (m instanceof Message.PUBLIC_KEY_GET)
        {
            LOGGER.log(Level.ALL, "received Public_Key_Get " + m.toString());
            ByteArrayWrapper key = new ByteArrayWrapper(((Message.PUBLIC_KEY_GET) m).getUsernameHash());
            if (publicKeyStorage.contains(key))
            {
                Message res = new Message.PUBLIC_KEY_RESULT(us, m.getOrigin(), ((Message.PUBLIC_KEY_GET) m).getUsernameHash(), publicKeyStorage.get(key), true);
                forwardMessage(res);
            } else
            {
                Message res = new Message.PUBLIC_KEY_RESULT(us, m.getOrigin(), ((Message.PUBLIC_KEY_GET) m).getUsernameHash(), new byte[0], false);
                forwardMessage(res);
            }
        } else if (m instanceof Message.PUBLIC_KEY_RESULT)
        {
            ByteArrayWrapper key = new ByteArrayWrapper(((Message.PUBLIC_KEY_RESULT) m).getUsernameHash());
            if (pendingPublicKeyGets.containsKey(key))
            {
                pendingPublicKeyGets.get(key).handleResult(new PublicKeyGetResult(((Message.PUBLIC_KEY_RESULT) m).isValid(), ((Message.PUBLIC_KEY_RESULT) m).getPublicKey()));
                pendingPublicKeyGets.remove(key);
            }
            else if (pendingPublicKeyPuts.containsKey(key))
            {
                pendingPublicKeyPuts.get(key).handleOffer(new PublicKeyPutResult(((Message.PUBLIC_KEY_RESULT) m).isValid()));
                pendingPublicKeyPuts.remove(key);
            }
            else
                LOGGER.log(Level.ALL, "Couldn't find GET_RESULT handler for " + Arrays.bytesToHex(key.data));
        }


        if (Message.LOG)
            printNeighboursAndFriends();

    }

    private class PublicKeyPutManager implements Runnable
    {
        private final Message.PUBLIC_KEY_PUT m;

        public PublicKeyPutManager(Message.PUBLIC_KEY_PUT m)
        {
            this.m = m;
        }

        public void run()
        {
            // check all previous recursion levels have the same value
            for (int level=0; level < m.getRecursion(); level++)
            {
                // TODO
            }

            // check all subsequent recursion levels are either empty, or the same
            for (int level=m.getRecursion(); level < PublicKey.PUBLIC_KEY_DUPLICATION; level++)
            {
                // TODO
            }
            // write key to our storage
            if (publicKeyStorage.accept(new ByteArrayWrapper(m.getHashedUsername()), m.getPublicKey().length))
            {
                publicKeyStorage.put(new ByteArrayWrapper(m.getHashedUsername()), m.getPublicKey());
                // succeeded, send response
                forwardMessage(new Message.PUBLIC_KEY_RESULT(us, m.getOrigin(), m.getHashedUsername(), m.getPublicKey(), true));
            }
            else
                forwardMessage(new Message.PUBLIC_KEY_RESULT(us, m.getOrigin(), m.getHashedUsername(), m.getPublicKey(), false));
        }
    }

    private void fillLeft(SortedMap<Long, Node> left, Set<Node> toSendECHO)
    {
        while ((leftNeighbours.size() < MAX_NEIGHBOURS) && (!left.isEmpty()))
        {
            Node nextClosest = left.get(left.lastKey());
            left.remove(nextClosest.node.id);
            if (nextClosest.isLost())
            {
                friends.remove(nextClosest.node.id);
                continue;
            }
            leftNeighbours.put(nextClosest.node.id, nextClosest);
            if (!nextClosest.wasRecentlyContacted())
            {
                nextClosest.sentECHO();
                toSendECHO.add(nextClosest);
            }
        }
    }

    private void fillRight(SortedMap<Long, Node> right, Set<Node> toSendECHO)
    {
        while ((rightNeighbours.size() < MAX_NEIGHBOURS) && (!right.isEmpty()))
        {
            Node nextClosest = right.get(right.firstKey());
            right.remove(nextClosest.node.id);
            if (nextClosest.isLost())
            {
                friends.remove(nextClosest.node.id);
                continue;
            }
            rightNeighbours.put(nextClosest.node.id, nextClosest);
            if (!nextClosest.wasRecentlyContacted())
            {
                nextClosest.sentECHO();
                toSendECHO.add(nextClosest);
            }
        }
    }

    public void printNeighboursAndFriends()
    {
        StringBuilder b = new StringBuilder();
        b.append(String.format("Left Neighbours:\n"));
        for (Node n : leftNeighbours.values())
            b.append(String.format("%s:%d id=%d recentlySeen=%s recentlyContacted=%s d=%d\n", n.node.addr.getHostAddress(), n.node.port, n.node.id, n.wasRecentlySeen(), n.wasRecentlyContacted(), n.node.d(us)));
        b.append(String.format("Right Neighbours:\n"));
        for (Node n : rightNeighbours.values())
            b.append(String.format("%s:%d id=%d recentlySeen=%s recentlyContacted=%s d=%d\n", n.node.addr.getHostAddress(), n.node.port, n.node.id, n.wasRecentlySeen(), n.wasRecentlyContacted(), n.node.d(us)));
        b.append(String.format("\nFriends:"));
        for (Node n : friends.values())
            b.append(String.format("%s:%d id=%d recentlySeen=%s d=%d\n", n.node.addr.getHostAddress(), n.node.port, n.node.id, n.wasRecentlySeen(), n.node.d(us)));
        b.append(String.format("\n"));
        LOGGER.log(Level.ALL, b.toString());
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

    private synchronized NodeID getClosest(Message m)
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

    // username + public key API
    public void sendPublicKeyPUT(byte[] username, byte[] publicKey, int recursion, PublicKeyPutHandler handler)
    {
        Message put = new Message.PUBLIC_KEY_PUT(us, username, publicKey, recursion);
        pendingPublicKeyPuts.put(new ByteArrayWrapper(username), handler);
        forwardMessage(put);
        handler.onStart();
    }

    public void sendPublicKeyGET(byte[] username, PublicKeyGetHandler handler)
    {
        Message put = new Message.PUBLIC_KEY_GET(us, username);
        pendingPublicKeyGets.put(new ByteArrayWrapper(username), handler);
        forwardMessage(put);
        handler.onStart();
    }

    // Fragment API
    public void sendPUT(byte[] key, int len, PutHandler handler)
    {
        Message put = new Message.PUT(us, key, len);
        pendingPuts.put(new ByteArrayWrapper(key), handler);
        forwardMessage(put);
        handler.onStart();
    }

    public void sendGET(byte[] key, GetHandler handler)
    {
        Message con = new Message.GET(us, key);
        pendingGets.put(new ByteArrayWrapper(key), handler);
        forwardMessage(con);
        handler.onStart();
    }

    public void sendCONTAINS(byte[] key, ContainsHandler handler)
    {
        Message con = new Message.GET(us, key);
        pendingGets.put(new ByteArrayWrapper(key), handler);
        forwardMessage(con);
        handler.onStart();
    }

    public static void test(int nodes) throws IOException
    {
        String[] args = new String[]{"-firstNode", "-port", "8000", "-logMessages", "-script", Args.getParameter("script")};
        main(args);
        args = new String[]{"-port", "8000", "-logMessages", "-contactIP", "127.0.0.1", "-contactPort", args[2]};
        for (int i = 0; i < nodes - 1; i++)
        {
            args[1] = 9000 + 1000 * i + "";
            main(args);
        }
    }

    public static void main(String[] args) throws IOException
    {
        Args.parse(args);
        if (Args.hasOption("help"))
        {
            Args.printOptions();
            System.exit(0);
        }
        if (Args.hasOption("firstNode"))
            for (File f : new File("log/").listFiles())
                f.delete();
        if (Args.hasParameter("test"))
        {
            test(Args.getInt("test", 6));
            return;
        }
        int port = Args.getInt("port", 8080);
        RoutingServer rs = new RoutingServer(port);
        rs.start();
        API api = new API(rs);
        if (Args.hasParameter("script"))
        {
            new Scripter(api, Args.getParameter("script")).start();
        }
    }
}
