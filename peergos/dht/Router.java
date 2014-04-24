package peergos.dht;

import akka.actor.*;
import akka.japi.Creator;
import akka.japi.pf.FI;
import akka.japi.pf.ReceiveBuilder;
import peergos.net.HTTPSMessenger;
import peergos.storage.Storage;
import peergos.util.*;
import peergos.util.Arrays;
import scala.PartialFunction;
import scala.runtime.BoxedUnit;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Router extends AbstractActor
{
    public static final int MAX_NEIGHBOURS = 2;
    public static final long MAX_STORAGE_SIZE = 1024 * 1024 * 1024L;
    public static final String DATA_DIR = "data/";

    private NodeID us;

    private SortedMap<Long, Node> leftNeighbours = new TreeMap();
    private SortedMap<Long, Node> rightNeighbours = new TreeMap();
    private SortedMap<Long, Node> friends = new TreeMap();
    private final Storage storage;
    public Logger LOGGER;
    private final Map<ByteArrayWrapper, ActorRef> pendingPuts = new ConcurrentHashMap();
    private final Map<ByteArrayWrapper, ActorRef> pendingGets = new ConcurrentHashMap();
    private final Random random = new Random(System.currentTimeMillis());
    private ActorRef messenger;
    private PartialFunction<Object, BoxedUnit> beginning;
    private PartialFunction<Object, BoxedUnit> waitingForInitialized;
    private PartialFunction<Object, BoxedUnit> initialized;
    private PartialFunction<Object, BoxedUnit> ready;
    private ActorRef lastOrderer;

    public Router(final int port) throws IOException
    {
        new File("log/").mkdir();
        us = new NodeID();
        String name = us.name() + ":" + us.port;
        LOGGER = Logger.getLogger(name);
        Handler handler = new FileHandler("log/" + name + ".log", 10 * 1024 * 1024, 7);
        LOGGER.addHandler(handler);
        LOGGER.setLevel(Level.ALL);
        storage = new Storage(new File(DATA_DIR), MAX_STORAGE_SIZE);
        messenger = context().actorOf(HTTPSMessenger.props(port, storage, LOGGER));

        beginning = ReceiveBuilder.match(HTTPSMessenger.INITIALIZE.class, new FI.UnitApply<HTTPSMessenger.INITIALIZE>() {
            @Override
            public void apply(HTTPSMessenger.INITIALIZE init) throws Exception {
                messenger.tell(init, self());
                context().become(waitingForInitialized);
                lastOrderer = sender();
            }
        }).build();
        waitingForInitialized = ReceiveBuilder.match(HTTPSMessenger.INITIALIZED.class, new FI.UnitApply<HTTPSMessenger.INITIALIZED>() {
            @Override
            public void apply(HTTPSMessenger.INITIALIZED j) throws Exception {
                    context().become(initialized);
                    lastOrderer.tell(new HTTPSMessenger.INITIALIZED(), self());
            }
        }).match(HTTPSMessenger.INITERROR.class, new FI.UnitApply<HTTPSMessenger.INITERROR>() {
            @Override
            public void apply(HTTPSMessenger.INITERROR j) throws Exception {
                context().become(beginning);
                lastOrderer.tell(new HTTPSMessenger.INITERROR(), self());
            }
        }).build();
        initialized = ReceiveBuilder.match(HTTPSMessenger.JOIN.class, new FI.UnitApply<HTTPSMessenger.JOIN>() {
            @Override
            public void apply(HTTPSMessenger.JOIN j) throws Exception {
                messenger.tell(new Letter(new Message.JOIN(us), j.addr, j.port), self());
                lastOrderer = sender();
                System.out.println(port + " received JOIN, set lastordered = "+lastOrderer);
            }
        }).match(HTTPSMessenger.JOINED.class, new FI.UnitApply<HTTPSMessenger.JOINED>() {
            @Override
            public void apply(HTTPSMessenger.JOINED m) throws Exception {
                context().become(ready);
                lastOrderer.tell(new HTTPSMessenger.JOINED(), self());
                LOGGER.log(Level.ALL, "Initial Storage server successfully joined DHT.");
            }
        }).match(Message.ECHO.class, new FI.UnitApply<Message.ECHO>() {
            @Override
            public void apply(Message.ECHO m) throws Exception {
                context().become(ready);
                actOnMessage(m);
                System.out.println(port + " sent JOINED to " +lastOrderer);
                lastOrderer.tell(new HTTPSMessenger.JOINED(), self());
                LOGGER.log(Level.ALL, "Storage server successfully joined DHT.");
            }
        }).match(HTTPSMessenger.JOINERROR.class, new FI.UnitApply<HTTPSMessenger.JOINERROR>() {
            @Override
            public void apply(HTTPSMessenger.JOINERROR j) throws Exception {
                lastOrderer.tell(new HTTPSMessenger.JOINERROR(), self());
            }
        }).build();

        ready = ReceiveBuilder.match(Message.class, new FI.UnitApply<Message>() {
            @Override
            public void apply(Message m) throws Exception {
                actOnMessage(m);
            }
        }).match(Letter.class, new FI.UnitApply<Letter>() {
            @Override
            public void apply(Letter m) throws Exception {
                messenger.tell(m, self());
            }
        }).match(MessageMailbox.class, new FI.UnitApply<MessageMailbox>() {
            @Override
            public void apply(MessageMailbox mb) throws Exception {
                Message m = mb.m;
                if (m instanceof Message.PUT)
                {
                    pendingPuts.put(new ByteArrayWrapper(((Message.PUT) m).getKey()), sender());
                }
                else if (m instanceof Message.GET)
                {
                    pendingGets.put(new ByteArrayWrapper(((Message.GET) m).getKey()), sender());
                }
                forwardMessage(m);
            }
        }).build();

        receive(beginning);
    }

    static Props props(final int port)
    {
        return Props.create(Router.class, new Creator<Router>() {
            @Override
            public Router create() throws Exception {
                return new Router(port);
            }
        });
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
        m.addNode(us);
        if (next != us)
        {
            m.addNode(getRandomNeighbour());
            messenger.tell(new Letter(m, next.addr, next.port), self());
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
                Message accept = new Message.PUT_ACCEPT((Message.PUT) m);
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
                ActorRef mailbox = pendingPuts.get(key);
                mailbox.tell(new PutOffer(target), self());
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
                long keyLong = Arrays.getLong(((Message.GET) m).getKey(), 0);
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
                ActorRef mailbox = pendingGets.get(key);
                mailbox.tell(new GetOffer(m.getHops().get(0), ((Message.GET_RESULT) m).getSize()), self());
                pendingGets.remove(key);
            }
            else
                LOGGER.log(Level.ALL, "Couldn't find GET_RESULT handler for " + Arrays.bytesToHex(key.data));
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
        Message echo = new Message.ECHO(target, leftNeighbours.values(), rightNeighbours.values());
        echo.addNode(us);
        messenger.tell(new Letter(echo, target.addr, target.port), self());
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

    public static ActorRef start(ActorSystem system, int port)
    {
        ActorRef master = system.actorOf(Router.props(port), "master"+port);
        return master;
    }
}
