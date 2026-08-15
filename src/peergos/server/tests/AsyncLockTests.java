package peergos.server.tests;

import org.junit.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.Assert.*;

public class AsyncLockTests {

    private static <T> CompletableFuture<T> read(AsyncLock<T> lock, java.util.function.Function<T, CompletionStage<T>> processor) {
        return lock.runWithReadLock(processor, () -> Futures.errored(new IllegalStateException("Unexpected updater call")));
    }

    @Test
    public void readsDontWaitForAnInflightWrite() {
        AsyncLock<Integer> lock = new AsyncLock<>(Futures.of(0));
        CompletableFuture<Integer> slowWrite = new CompletableFuture<>();
        CompletableFuture<Integer> write = lock.runWithLock(x -> slowWrite);

        CompletableFuture<Integer> result = read(lock, x -> Futures.of(42));

        assertTrue("read completed whilst a write is in flight", result.isDone());
        assertEquals(42, (int) result.join());
        assertFalse(write.isDone());

        slowWrite.complete(1);
        assertEquals(1, (int) write.join());
    }

    @Test
    public void readsAreSerialisedWithEachOther() {
        AsyncLock<Integer> lock = new AsyncLock<>(Futures.of(0));
        CompletableFuture<Integer> slowRead = new CompletableFuture<>();
        List<Integer> order = new ArrayList<>();

        CompletableFuture<Integer> first = read(lock, x -> slowRead.thenApply(v -> {
            order.add(1);
            return v;
        }));
        CompletableFuture<Integer> second = read(lock, x -> {
            order.add(2);
            return Futures.of(2);
        });

        assertTrue("second read hasn't started", order.isEmpty());
        assertFalse(second.isDone());

        slowRead.complete(1);
        assertEquals(Arrays.asList(1, 2), order);
        assertEquals(1, (int) first.join());
        assertEquals(2, (int) second.join());
    }

    @Test
    public void aFailedReadDoesntPoisonTheReadQueue() {
        AsyncLock<Integer> lock = new AsyncLock<>(Futures.of(7));

        CompletableFuture<Integer> failed = read(lock, x -> Futures.errored(new RuntimeException("boom")));
        assertTrue(failed.isCompletedExceptionally());

        List<Integer> startedFrom = new ArrayList<>();
        CompletableFuture<Integer> next = read(lock, x -> {
            startedFrom.add(x);
            return Futures.of(x + 1);
        });
        assertEquals("previous value retained", Arrays.asList(7), startedFrom);
        assertEquals(8, (int) next.join());
    }

    @Test
    public void aFailedInitialValueIsRecoveredForReads() {
        AsyncLock<Integer> lock = new AsyncLock<>(Futures.errored(new RuntimeException("boom")));

        CompletableFuture<Integer> failed = lock.runWithReadLock(x -> Futures.of(x), () -> Futures.of(5));
        assertTrue(failed.isCompletedExceptionally());

        List<Integer> startedFrom = new ArrayList<>();
        CompletableFuture<Integer> next = read(lock, x -> {
            startedFrom.add(x);
            return Futures.of(x);
        });
        assertEquals(Arrays.asList(5), startedFrom);
        assertEquals(5, (int) next.join());
    }

    @Test
    public void aReadDoesntOverwriteAnInflightWritesResult() {
        AsyncLock<Integer> lock = new AsyncLock<>(Futures.of(0));
        CompletableFuture<Integer> slowRead = new CompletableFuture<>();
        CompletableFuture<Integer> reading = read(lock, x -> slowRead);

        // a write starts and completes whilst the read is in flight
        assertEquals(5, (int) lock.runWithLock(x -> Futures.of(5)).join());

        // the read returns a value retrieved before the write
        slowRead.complete(1);
        assertEquals(1, (int) reading.join());

        List<Integer> startedFrom = new ArrayList<>();
        lock.runWithLock(x -> {
            startedFrom.add(x);
            return Futures.of(x);
        }).join();
        assertEquals("write starts from the written value", Arrays.asList(5), startedFrom);
    }

    @Test
    public void aWriteIsVisibleToTheNextRead() {
        AsyncLock<Integer> lock = new AsyncLock<>(Futures.of(0));
        lock.runWithLock(x -> Futures.of(9)).join();

        List<Integer> startedFrom = new ArrayList<>();
        read(lock, x -> {
            startedFrom.add(x);
            return Futures.of(x);
        }).join();
        assertEquals(Arrays.asList(9), startedFrom);
    }

    @Test
    public void aReadRefreshesAnIdleWriteQueue() {
        AsyncLock<Integer> lock = new AsyncLock<>(Futures.of(0));
        read(lock, x -> Futures.of(3)).join();

        List<Integer> startedFrom = new ArrayList<>();
        lock.runWithLock(x -> {
            startedFrom.add(x);
            return Futures.of(x);
        }).join();
        assertEquals(Arrays.asList(3), startedFrom);
    }

    @Test
    public void writesAreStillSerialised() {
        AsyncLock<Integer> lock = new AsyncLock<>(Futures.of(0));
        CompletableFuture<Integer> slowWrite = new CompletableFuture<>();
        List<Integer> order = new ArrayList<>();

        CompletableFuture<Integer> first = lock.runWithLock(x -> slowWrite.thenApply(v -> {
            order.add(1);
            return v;
        }));
        CompletableFuture<Integer> second = lock.runWithLock(x -> {
            order.add(2);
            return Futures.of(x + 1);
        });

        assertTrue(order.isEmpty());
        slowWrite.complete(10);
        assertEquals(Arrays.asList(1, 2), order);
        assertEquals(10, (int) first.join());
        assertEquals(11, (int) second.join());
    }
}
