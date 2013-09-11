package defiance.dht;


public interface RequestHandler
{
    public void started();

    public void setFailed();
    public boolean isFailed();

    public void setCompleted();
    public boolean isCompleted();
}
