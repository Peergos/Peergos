package defiance.dht;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;

public abstract class AbstractRequestHandler implements RequestHandler
{
    public static final long TIMEOUT = 60*1000;
    private final long duration;
    private boolean completed;
    private static final Executor timeoutPool = Executors.newCachedThreadPool();

    public AbstractRequestHandler()
    {
        this(TIMEOUT);
    }

    public AbstractRequestHandler(long duration)
    {
        this.duration = duration;
    }

    @Override
    public synchronized void onStart()
    {
        final long expireTime = System.currentTimeMillis() + duration;
        timeoutPool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(expireTime);
                } catch (InterruptedException f) {
                }
                onError(new TimeoutException("Request timedout."));
            }
        }
        );
    }

    @Override
    public synchronized void onError(Throwable e)
    {
        if (!completed)
        {
            completed = true;
            handleError(e);
        }
    }

    @Override
    public synchronized void onComplete()
    {
        completed = true;
        handleComplete();
    }

    protected abstract void handleComplete();
    protected abstract void handleError(Throwable e);
}
