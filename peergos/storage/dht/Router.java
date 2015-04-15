package peergos.storage.dht;

import peergos.storage.net.HttpMessenger;
import peergos.storage.net.HttpsMessenger;
import peergos.storage.Storage;
import peergos.util.*;
import peergos.util.ArrayOps;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Router
{
    public static final int MAX_NEIGHBOURS = 2;
    public static final long MAX_STORAGE_SIZE = 1024 * 1024 * 1024L;
    public static final String DATA_DIR = "data/";

    private NodeID us;

    private SortedMap<Long, Node> leftNeighbours = new TreeMap();
    private SortedMap<Long, Node> rightNeighbours = new TreeMap();
    private SortedMap<Long, Node> friends = new TreeMap();
    public final Storage storage;
    public Logger LOGGER;
    private final Map<ByteArrayWrapper, peergos.util.CompletableFuture> pendingPuts = new ConcurrentHashMap();
    private final Map<ByteArrayWrapper, peergos.util.CompletableFuture> pendingGets = new ConcurrentHashMap();
    private final Random random = new Random(System.currentTimeMillis());
    private HttpMessenger messenger;
    private HttpsMessenger userAPI;
    private BlockingQueue queue = new ArrayBlockingQueue(200);


    public Router(String donor, InetSocketAddress userAPIAddress, InetSocketAddress messengerAddress) throws IOException
    {
        new File("log/").mkdir();
        us = new NodeID(messengerAddress);
        String name = us.name() + "_" + us.external.getPort();
        LOGGER = Logger.getLogger(name);
        Handler handler = new FileHandler("log/" + name + ".log", 10 * 1024 * 1024, 7);
        LOGGER.addHandler(handler);
        LOGGER.setLevel(Level.ALL);
        storage = new Storage(donor, new File(DATA_DIR), MAX_STORAGE_SIZE, messengerAddress);

        String hostname = Args.getArg("domain", "localhost");

        InetSocketAddress httpsMessengerAddress = new InetSocketAddress(hostname, userAPIAddress.getPort());
        userAPI = new HttpsMessenger(httpsMessengerAddress, LOGGER, this);

        InetSocketAddress local = new InetSocketAddress(hostname, messengerAddress.getPort());
        messenger = new HttpMessenger(local, storage, LOGGER, this);
    }

    public NodeID address() {
        return us;
    }

    public void init(InetSocketAddress addr) throws IOException {
        messenger.sendLetter(new Letter(new Message.JOIN(us), addr));
        // wait for response

        LOGGER.log(Level.ALL, "Initial Storage server successfully joined DHT.");
    }

    public void run() {
        while (true) {
            try {
                Object m = queue.take();
                if (m instanceof Message)
                    actOnMessage((Message) m);
                else if (m instanceof Letter)
                    messenger.sendLetter((Letter) m);

            } catch (InterruptedException e) {}
        }
    }

    public Future<Object> ask(Message m) {
        LOGGER.log(Level.ALL, "Asking "+m.name());
        peergos.util.CompletableFuture f = new peergos.util.CompletableFuture(new Callable() {
            @Override
            public Object call() throws Exception {
                return null;
            }
        });
        if (m instanceof Message.PUT)
        {
            pendingPuts.put(new ByteArrayWrapper(((Message.PUT) m).getKey()), f);
        }
        else if (m instanceof Message.GET)
        {
            pendingGets.put(new ByteArrayWrapper(((Message.GET) m).getKey()), f);
        }
        forwardMessage(m);
        return f;
    }

    public void enqueueMessage(Message m) {
        queue.add(m);
    }

    private void actOnMessage(Message m)
    {
        if (Message.LOG)
        {
            if (m.getHops().size() > 0)
                LOGGER.log(Level.ALL, String.format("Received %s from %s with target %d\n", m.name(), m.getHops().get(0).external, m.getTarget()));
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
        m.addNode(us);
        if (!next.equals(us))
        {
            LOGGER.log(Level.ALL, "Sending "+m.name() +" to "+next.name());
            m.addNode(getRandomNeighbour());
            messenger.sendLetter(new Letter(m, next.external));
        } else {
            //avoid infinite loop of forwarding message to ourselves
            List<NodeID> hops = m.getHops();
            if (hops.size() > 5) {
                for (NodeID n: hops)
                    System.out.printf(n.toString());
                System.out.println();
            }
            escalateMessage(m); //bypass network in this unlikely case
        }
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
            if (!existing.node.external.equals(n.external))
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
            LOGGER.log(Level.ALL, String.format("Escalated to DHTAPI %s\n", m.name()));
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
            Message.PUT put = (Message.PUT) m;
            if (storage.accept(new ByteArrayWrapper(put.getKey()), put.getSize(), put.getOwner(), put.getSharingKey(), put.getMapKey(), put.getProof()))
            {
                // send PUT accept message
                Message accept = new Message.PUT_ACCEPT((Message.PUT) m);
                forwardMessage(accept);
            } else
            {
                LOGGER.log(Level.ALL, "Rejected Put request.");
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
                peergos.util.CompletableFuture success = pendingPuts.get(key);
                success.addResult(new PutOffer(target));
                pendingPuts.remove(key);
            }
        } else if (m instanceof Message.GET)
        {
            ByteArrayWrapper key = new ByteArrayWrapper(((Message.GET) m).getKey());
            if (storage.contains(key))
            {
                Message res = new Message.GET_RESULT((Message.GET) m, storage.sizeOf(key));
                forwardMessage(res);
            } else
            {
                // forward to two neighbours as they might have it (if we were the original target of the request
                long keyLong = ArrayOps.getLong(((Message.GET) m).getKey(), 0);
                if (leftNeighbours.size() != 0)
                {
                    Node left = leftNeighbours.get(leftNeighbours.lastKey());
                    if (Math.abs(keyLong - us.id) < left.node.d(keyLong))
                    {
                        Message newput = new Message.GET(((Message.GET) m).getKey(), left.node.id);
                        forwardMessage(newput);
                    }
                }
                if (rightNeighbours.size() != 0)
                {
                    Node right = rightNeighbours.get(rightNeighbours.firstKey());
                    if (Math.abs(keyLong - us.id) < right.node.d(keyLong))
                    {
                        Message newput = new Message.GET(((Message.GET) m).getKey(), right.node.id);
                        forwardMessage(newput);
                    }
                }
            }
        } else if (m instanceof Message.GET_RESULT)
        {
            ByteArrayWrapper key = new ByteArrayWrapper(((Message.GET_RESULT) m).getKey());
            if (pendingGets.containsKey(key))
            {
                peergos.util.CompletableFuture success = pendingGets.get(key);
                success.addResult(new GetOffer(m.getHops().get(0), ((Message.GET_RESULT) m).getSize()));
                pendingGets.remove(key);
            }
            else
                LOGGER.log(Level.ALL, "Couldn't find GET_RESULT handler for " + ArrayOps.bytesToHex(key.data));
        }

        if (Message.LOG)
            printNeighboursAndFriends();

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
            b.append(String.format("%s id=%d recentlySeen=%s recentlyContacted=%s d=%d\n", n.node.external, n.node.id, n.wasRecentlySeen(), n.wasRecentlyContacted(), n.node.d(us)));
        b.append(String.format("Right Neighbours:\n"));
        for (Node n : rightNeighbours.values())
            b.append(String.format("%s id=%d recentlySeen=%s recentlyContacted=%s d=%d\n", n.node.external, n.node.id, n.wasRecentlySeen(), n.wasRecentlyContacted(), n.node.d(us)));
        b.append(String.format("\nFriends:"));
        for (Node n : friends.values())
            b.append(String.format("%s id=%d recentlySeen=%s d=%d\n", n.node.external, n.node.id, n.wasRecentlySeen(), n.node.d(us)));
        b.append(String.format("\n"));
        LOGGER.log(Level.ALL, b.toString());
    }

    private void sendECHO(NodeID target)
    {
        Message echo = new Message.ECHO(target, leftNeighbours.values(), rightNeighbours.values());
        echo.addNode(us);
        messenger.sendLetter(new Letter(echo, target.external));
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
}
