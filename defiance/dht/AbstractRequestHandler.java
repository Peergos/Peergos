package defiance.dht;

public abstract class AbstractRequestHandler implements RequestHandler
{
    public static final long TIMEOUT = 60*1000;
    private boolean failed = false;
    private long startTime;
    private volatile boolean completed = false;

    public AbstractRequestHandler()
    {
    }

    @Override
    public synchronized void started()
    {
        startTime = System.currentTimeMillis();
    }

    @Override
    public synchronized  void setFailed()
    {
        completed = true;
        failed = true;
    }

    @Override
    public synchronized  boolean isFailed()
    {
        return failed || (System.currentTimeMillis() - startTime > TIMEOUT);
    }

    @Override
    public synchronized  void setCompleted()
    {
        completed = true;
    }

    @Override
    public synchronized  boolean isCompleted()
    {
        return completed;
    }
}
