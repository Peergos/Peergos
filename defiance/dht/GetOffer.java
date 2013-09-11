package defiance.dht;

public class GetOffer
{
    NodeID target;
    private int size;

    GetOffer(NodeID t, int size)
    {
        this.target = t;
        this.size = size;
    }

    public NodeID getTarget()
    {
        return target;
    }

    public int getSize()
    {
        return size;
    }
}
