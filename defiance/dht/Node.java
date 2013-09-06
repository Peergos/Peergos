package defiance.dht;

public class Node
{
    public static enum State {Good, Waiting, Lost}
    public long lastSeen;
    public State state;
    public NodeID node;

    public Node(NodeID node)
    {
        lastSeen = System.currentTimeMillis();
        state = State.Good;
        this.node = node;
    }

    public boolean isLost()
    {
        return state == State.Lost;
    }

    public void receivedContact()
    {
        lastSeen = System.currentTimeMillis();
        state = State.Good;
    }

    public void sendECHO()
    {
        state = State.Waiting;
    }

    public void timedOut()
    {
        state = State.Lost;
    }
}
