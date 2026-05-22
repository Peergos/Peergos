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

    /** For tasks that block internally (e.g. call .join()). Uses virtual threads where available,
     *  falls back to a cached thread pool on Android so blocking never exhausts a ForkJoinPool. */
    public static ExecutorService newBlockingPool() {
        boolean isAndroid = "The Android Project".equals(System.getProperty("java.vm.vendor"));
        if (isAndroid)
            return Executors.newCachedThreadPool();
        else {
            try {
                return Executors.newVirtualThreadPerTaskExecutor();
            } catch (Throwable t) {
                return Executors.newCachedThreadPool();
            }
        }
    }

    public static ExecutorService newPool(int threads, String threadNamePrefix) {
        boolean isAndroid = "The Android Project".equals(System.getProperty("java.vm.vendor"));
        if (isAndroid)
            return newFJPool(threads, threadNamePrefix);
        else {
            try {
                return Executors.newVirtualThreadPerTaskExecutor();
            } catch (Throwable t) {
                return newFJPool(threads, threadNamePrefix);
            }
        }
    }

    public static ForkJoinPool newFJPool(int threads, String threadNamePrefix) {
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
