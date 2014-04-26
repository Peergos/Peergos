package peergos.storage.dht;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;

public abstract class AbstractRequestHandler implements RequestHandler
{
    public static final long TIMEOUT = 1*1000;
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
        timeoutPool.execute(new Runnable(){ public void run(){
                try {
                    Thread.sleep(duration);
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
