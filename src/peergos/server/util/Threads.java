package peergos.server.util;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

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

    /** A virtual thread per task, but never more than {@code maxConcurrent} of them at once; further
     *  tasks are rejected rather than queued.
     *
     *  This exists for the executors behind a {@link com.sun.net.httpserver.HttpServer}, where the
     *  unbounded {@link #newPool} is actively dangerous. The server hands one task per accepted
     *  connection to its executor and that task owns the socket for its whole life, so an unbounded
     *  executor means an unbounded number of open descriptors whenever handlers block for longer
     *  than clients take to arrive - which is what a p2p proxy handler waiting on an unreachable
     *  peer does. Queueing would not help: a queued exchange holds its descriptor just as a running
     *  one does. Only refusing the work releases it, so over the cap we reject, and the server closes
     *  the connection.
     *
     *  Rejecting caps descriptors but cannot on its own keep a node healthy: if handlers block
     *  forever, a bounded pool just fills up permanently. It has to be paired with the request and
     *  response timeouts set in {@link #boundHttpServerExchanges}. */
    public static ExecutorService newBoundedPool(int maxConcurrent, String threadNamePrefix) {
        boolean isAndroid = "The Android Project".equals(System.getProperty("java.vm.vendor"));
        if (isAndroid)
            return newFJPool(maxConcurrent, threadNamePrefix);
        ExecutorService delegate;
        try {
            delegate = Executors.newVirtualThreadPerTaskExecutor();
        } catch (Throwable t) {
            return newFJPool(maxConcurrent, threadNamePrefix);
        }
        Semaphore permits = new Semaphore(maxConcurrent);
        return new BoundedExecutor(delegate, permits, maxConcurrent, threadNamePrefix);
    }

    /** Delegates to a virtual thread executor, refusing tasks once maxConcurrent are in flight. Only
     *  execute() is gated - the submit()/invoke() family inherited from AbstractExecutorService all
     *  funnel through it. */
    private static class BoundedExecutor extends AbstractExecutorService {
        private final ExecutorService delegate;
        private final Semaphore permits;
        private final int maxConcurrent;
        private final String name;
        private final AtomicLong rejected = new AtomicLong();

        BoundedExecutor(ExecutorService delegate, Semaphore permits, int maxConcurrent, String name) {
            this.delegate = delegate;
            this.permits = permits;
            this.maxConcurrent = maxConcurrent;
            this.name = name;
        }

        @Override
        public void execute(Runnable task) {
            if (! permits.tryAcquire()) {
                long n = rejected.incrementAndGet();
                // one line per power of two, so a sustained overload is visible without flooding
                if (Long.bitCount(n) == 1)
                    Logging.LOG().warning(name + " at its concurrency limit of " + maxConcurrent
                            + ", rejected " + n + " tasks so far");
                throw new RejectedExecutionException(name + " has " + maxConcurrent + " tasks in flight");
            }
            boolean started = false;
            try {
                delegate.execute(() -> {
                    try {
                        task.run();
                    } finally {
                        permits.release();
                    }
                });
                started = true;
            } finally {
                if (! started)
                    permits.release();
            }
        }

        /** How many tasks are running right now. */
        public int inFlight() {
            return maxConcurrent - permits.availablePermits();
        }

        public long rejectedCount() {
            return rejected.get();
        }

        @Override
        public void shutdown() {
            delegate.shutdown();
        }

        @Override
        public List<Runnable> shutdownNow() {
            return delegate.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
            return delegate.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return delegate.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }
    }

    /** Turns on the JDK http server's exchange reaper, which is off by default.
     *
     *  Without it a handler that never returns holds its connection for the life of the process. The
     *  server tracks such connections but only sweeps them if one of these timeouts is positive, so
     *  by default a blocked handler leaks a descriptor permanently. Both are read once, in a static
     *  initialiser of sun.net.httpserver.ServerConfig, so this has to run before any HttpServer is
     *  created - hence its call site in Main rather than next to each server. */
    public static void boundHttpServerExchanges() {
        setIfAbsent("sun.net.httpserver.maxReqTime", "60");
        setIfAbsent("sun.net.httpserver.maxRspTime", "600");
    }

    private static void setIfAbsent(String property, String value) {
        if (System.getProperty(property) == null)
            System.setProperty(property, value);
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
