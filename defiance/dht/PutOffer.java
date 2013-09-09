package defiance.dht;

public class PutOffer
{
    NodeID target;

    PutOffer(NodeID t)
    {
        this.target = t;
    }

    public NodeID getTarget()
    {
        return target;
    }
}
