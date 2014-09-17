package peergos.storage.dht;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

public class FutureWrapper
{
    public static void followWith(final Future fut, final OnSuccess success, final OnFailure failure, ExecutorService executor) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    success.onSuccess(fut.get());
                } catch (ExecutionException e) {
                    failure.onFailure(e.getCause());
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        });
    }
}
