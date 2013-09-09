package defiance.dht;

public class Node
{
    public long lastSeen;
    public long lastSentTo;
    public NodeID node;
    public static final long NEIGHBOUR_TIMEOUT = 2 * 60 * 1000L; // 2 minutes

    public Node(NodeID node)
    {
        lastSeen = System.currentTimeMillis();
        this.node = node;
    }

    public boolean isLost()
    {
        return System.currentTimeMillis() > lastSeen + 2 * NEIGHBOUR_TIMEOUT; // allow for spread around NEIGHBOUR_TIMEOUT
    }

    public void receivedContact()
    {
        lastSeen = System.currentTimeMillis();
    }

    public boolean wasRecentlySeen()
    {
        return System.currentTimeMillis() < lastSeen + NEIGHBOUR_TIMEOUT/2; // allow for spread around NEIGHBOUR_TIMEOUT
    }

    public void sentECHO()
    {
        lastSentTo = System.currentTimeMillis();
    }

    public boolean wasRecentlyContacted()
    {
        return System.currentTimeMillis() < lastSentTo + NEIGHBOUR_TIMEOUT/2;
    }
}
