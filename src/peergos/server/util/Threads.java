package peergos.server.util;

import java.util.concurrent.*;

public class Threads {

    private static ForkJoinPool.ForkJoinWorkerThreadFactory getThreadFactory(String namePrefix) {
        return new ForkJoinPool.ForkJoinWorkerThreadFactory() {
            @Override
            public ForkJoinWorkerThread newThread(ForkJoinPool pool) {
                final ForkJoinWorkerThread worker = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
                worker.setName(namePrefix + worker.getPoolIndex());
                return worker;
            }
        };
    }

    public static ForkJoinPool newPool(int threads, String threadNamePrefix) {
        return new ForkJoinPool(threads, getThreadFactory(threadNamePrefix), new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable t) {
                t.printStackTrace();
            }
        }, true);
    }

    public static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
