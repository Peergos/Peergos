package peergos.server.storage;

import io.netty.handler.ssl.SslClosedEngineException;
import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;
import peergos.server.util.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.bases.Base64;
import peergos.shared.storage.*;
import peergos.shared.storage.auth.*;
import peergos.shared.util.*;

import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.util.logging.*;

/** A second S3 bucket holding a copy of every cbor block in an S3BlockStorage, under the same keys.
 *  It is only ever a cache: anything missing here is read from the main store. So writes to it are best
 *  effort, and a failed one is queued for retry rather than failing the write to the main store.
 */
public class S3CborMirror {
    private static final Logger LOG = Logger.getGlobal();

    private static final Counter mirrorGets = Counter.build()
            .name("s3_cbor_mirror_gets")
            .help("Number of block gets to the S3 cbor mirror")
            .register();
    private static final Counter mirrorMisses = Counter.build()
            .name("s3_cbor_mirror_misses")
            .help("Number of block gets to the S3 cbor mirror that fell back to the main store")
            .register();
    private static final Counter mirrorPuts = Counter.build()
            .name("s3_cbor_mirror_puts")
            .help("Number of block puts to the S3 cbor mirror")
            .register();
    private static final Counter mirrorPutFailures = Counter.build()
            .name("s3_cbor_mirror_put_failures")
            .help("Number of failed block puts to the S3 cbor mirror, which are queued for retry")
            .register();
    private static final Counter mirrorPutsDropped = Counter.build()
            .name("s3_cbor_mirror_puts_dropped")
            .help("Number of block puts to the S3 cbor mirror given up on, which only a backfill or a later read will restore")
            .register();
    private static final Gauge mirrorPutsQueued = Gauge.build()
            .name("s3_cbor_mirror_puts_queued")
            .help("Number of block puts to the S3 cbor mirror waiting to be retried")
            .register();

    private static final int MAX_QUEUED = 100_000;
    private static final int MAX_PUT_ATTEMPTS = 10;
    private static final int RETRY_WORKERS = 4;
    private static final long INITIAL_BACKOFF_MILLIS = 1_000, MAX_BACKOFF_MILLIS = 60_000;

    /** A block waiting in the retry buffer to be written to the mirror. */
    private static class PendingPut implements Delayed {
        final PublicKeyHash owner;
        final Cid cid;
        final int failures;
        final long readyAt;

        PendingPut(PublicKeyHash owner, Cid cid, int failures, long readyAt) {
            this.owner = owner;
            this.cid = cid;
            this.failures = failures;
            this.readyAt = readyAt;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(readyAt - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
        }

        @Override
        public int compareTo(Delayed o) {
            return Long.compare(readyAt, ((PendingPut) o).readyAt);
        }
    }

    private final DelayQueue<PendingPut> pending = new DelayQueue<>();
    private final AtomicLong throttleBackoff = new AtomicLong(INITIAL_BACKOFF_MILLIS);
    private volatile long throttledUntil = 0;
    private volatile long lastDropLog = 0;

    private final String region, bucket, folder, host;
    private final boolean useHttps;
    private final String accessKeyId, secretKey;
    private final Hasher hasher;
    private final BlockBuffer retryBuffer;
    private volatile BiFunction<PublicKeyHash, Cid, String> keys;
    private final AtomicBoolean retrying = new AtomicBoolean(false);

    /**
     * @param retryBuffer Where blocks wait, across restarts, after a write to the mirror fails
     */
    public S3CborMirror(S3Config config, BlockBuffer retryBuffer, Hasher hasher) {
        this.region = config.region;
        this.bucket = config.bucket;
        this.host = config.getHost();
        this.useHttps = ! host.endsWith("localhost") && ! host.contains("localhost:");
        this.folder = (useHttps ? "" : bucket + "/") + (config.path.isEmpty() || config.path.endsWith("/") ? config.path : config.path + "/");
        this.accessKeyId = config.accessKey;
        this.secretKey = config.secretKey;
        this.hasher = hasher;
        this.retryBuffer = retryBuffer;
        LOG.info("Mirroring cbor blocks to S3 at " + config.regionEndpoint + ", bucket " + config.bucket + ", path: " + config.path);
    }

    /** Start retrying the writes in the retry buffer, including any left from before a restart.
     *
     * @param keys The key of a block in the main store, which is also its key here
     */
    public void startRetries(BiFunction<PublicKeyHash, Cid, String> keys) {
        this.keys = keys;
        if (! retrying.compareAndSet(false, true))
            return;
        for (int i = 0; i < RETRY_WORKERS; i++) {
            Thread retrier = new Thread(this::retryPendingPuts, "S3-cbor-mirror-retry-" + i);
            retrier.setDaemon(true);
            retrier.start();
        }
        Thread.ofVirtual().start(() -> {
            long now = System.currentTimeMillis();
            retryBuffer.applyToAll((owner, cid) -> enqueue(new PendingPut(owner, cid, 0, now)));
        });
    }

    /** Throttling and transient network errors, which are worth waiting out. */
    public static boolean isRateLimited(Throwable e) {
        Throwable cause = S3BlockStorage.getRootCause(e);
        if (S3BlockStorage.isRateLimitedException(e) || S3BlockStorage.isRateLimitedException(cause))
            return true;
        if (S3BlockStorage.isCausedBy(e, RateLimitException.class, SocketTimeoutException.class,
                SSLException.class, SslClosedEngineException.class, SocketException.class))
            return true;
        // HttpUtil.head reports codes other than 404, 502 and 503 only by their number
        String msg = cause.getMessage();
        return msg != null && (msg.equals("HTTP 429") || msg.equals("HTTP 500"));
    }

    /** Retry rate limits for minutes rather than seconds, for bulk work like a backfill that can wait. */
    public static <V> V withBackoff(Supplier<V> req) {
        long sleep = INITIAL_BACKOFF_MILLIS;
        for (int i = 0; ; i++) {
            try {
                return req.get();
            } catch (Exception e) {
                if (i + 1 >= MAX_PUT_ATTEMPTS || ! isRateLimited(e))
                    throw e;
                Threads.sleep(sleep);
                sleep = Math.min(sleep * 2, MAX_BACKOFF_MILLIS);
            }
        }
    }

    public boolean isThrottled() {
        return System.currentTimeMillis() < throttledUntil;
    }

    private void throttle() {
        long wait = throttleBackoff.getAndUpdate(b -> Math.min(b * 2, MAX_BACKOFF_MILLIS));
        throttledUntil = Math.max(throttledUntil, System.currentTimeMillis() + wait);
    }

    /** Write a block, or buffer it for retry. Never throws, so the mirror can't fail a write to the main store. */
    public void putBestEffort(PublicKeyHash owner, Cid cid, String key, byte[] data) {
        if (! isThrottled()) {
            try {
                put(key, cid, data);
                return;
            } catch (Exception e) {
                if (isRateLimited(e))
                    throttle();
                else
                    LOG.log(Level.WARNING, "S3 cbor mirror error writing " + key + ", will retry: " + e.getMessage(), e);
            }
        }
        mirrorPutFailures.inc();
        buffer(owner, cid, key, data, Math.max(throttledUntil, System.currentTimeMillis() + INITIAL_BACKOFF_MILLIS));
    }

    /** Copy in a block that a read found missing, unless the mirror is busy enough to be throttling us. */
    public void repair(PublicKeyHash owner, Cid cid, String key, byte[] data) {
        if (isThrottled())
            return;
        buffer(owner, cid, key, data, System.currentTimeMillis());
    }

    private void buffer(PublicKeyHash owner, Cid cid, String key, byte[] data, long readyAt) {
        if (pending.size() >= MAX_QUEUED) {
            drop(key, "retry buffer is full");
            return;
        }
        try {
            retryBuffer.put(owner, cid, data).join();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "S3 cbor mirror couldn't buffer " + key + " for retry", e);
            drop(key, "couldn't buffer it");
            return;
        }
        enqueue(new PendingPut(owner, cid, 0, readyAt));
    }

    private void enqueue(PendingPut put) {
        pending.add(put);
        mirrorPutsQueued.inc();
    }

    private void drop(String key, String reason) {
        mirrorPutsDropped.inc();
        long now = System.currentTimeMillis();
        if (now - lastDropLog > 60_000) {
            lastDropLog = now;
            LOG.warning("S3 cbor mirror dropped " + key + " (" + reason + "), run a backfill to restore any dropped blocks");
        }
    }

    private void retryPendingPuts() {
        while (true) {
            PendingPut next;
            try {
                next = pending.take();
            } catch (InterruptedException e) {
                return;
            }
            mirrorPutsQueued.dec();
            if (isThrottled()) {
                enqueue(new PendingPut(next.owner, next.cid, next.failures, throttledUntil));
                continue;
            }
            String key = null;
            try {
                key = keys.apply(next.owner, next.cid);
                Optional<byte[]> data = retryBuffer.get(next.owner, next.cid).join();
                // already written by an earlier entry for the same block
                if (data.isEmpty())
                    continue;
                put(key, next.cid, data.get());
                retryBuffer.delete(next.owner, next.cid).join();
                throttleBackoff.set(INITIAL_BACKOFF_MILLIS);
            } catch (Exception e) {
                if (isRateLimited(e)) {
                    throttle();
                    enqueue(new PendingPut(next.owner, next.cid, next.failures, throttledUntil));
                } else if (next.failures + 1 >= MAX_PUT_ATTEMPTS) {
                    LOG.log(Level.WARNING, "S3 cbor mirror giving up writing " + key, e);
                    retryBuffer.delete(next.owner, next.cid).join();
                    drop(key, "too many failures");
                } else {
                    long delay = Math.min(INITIAL_BACKOFF_MILLIS << next.failures, MAX_BACKOFF_MILLIS);
                    enqueue(new PendingPut(next.owner, next.cid, next.failures + 1, System.currentTimeMillis() + delay));
                }
            }
        }
    }

    /**
     * @return the block, or empty if it is absent or could not be read
     */
    public Optional<byte[]> get(String key, Optional<Pair<Integer, Integer>> range) {
        PresignedUrl getUrl = S3Request.preSignGet(folder + key, Optional.of(600), range,
                S3AdminRequests.asAwsDate(ZonedDateTime.now()), host, region, Optional.empty(), accessKeyId, secretKey, useHttps, hasher).join();
        try {
            byte[] block = HttpUtil.getWithVersion(getUrl).left;
            mirrorGets.inc();
            return Optional.of(block);
        } catch (Exception e) {
            mirrorMisses.inc();
            Throwable cause = S3BlockStorage.getRootCause(e);
            String msg = cause.getMessage();
            boolean notFound = cause instanceof FileNotFoundException || (msg != null && msg.contains("<Code>NoSuchKey</Code>"));
            if (! notFound)
                LOG.log(Level.WARNING, "S3 cbor mirror error reading " + key + ": " + msg, e);
            return Optional.empty();
        }
    }

    public boolean has(String key) {
        PresignedUrl headUrl = S3Request.preSignHead(folder + key, Optional.of(60),
                S3AdminRequests.asAwsDate(ZonedDateTime.now()), host, region, Optional.empty(), accessKeyId, secretKey, useHttps, hasher).join();
        try {
            HttpUtil.head(headUrl);
            return true;
        } catch (FileNotFoundException e) {
            return false;
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("<Code>NoSuchKey</Code>"))
                return false;
            if (isRateLimited(e))
                throw new RateLimitException();
            throw new RuntimeException(e);
        }
    }

    public void put(String key, Cid cid, byte[] data) {
        Map<String, String> extraHeaders = new TreeMap<>();
        extraHeaders.put("Content-Type", "application/octet-stream");
        PresignedUrl putUrl = S3Request.preSignPut(folder + key, data.length, ArrayOps.bytesToHex(cid.getHash()), Optional.empty(), false,
                S3AdminRequests.asAwsDate(ZonedDateTime.now()), host, extraHeaders, region, accessKeyId, secretKey, useHttps, hasher).join();
        try {
            HttpUtil.putWithVersion(putUrl, data);
            mirrorPuts.inc();
        } catch (Exception e) {
            if (isRateLimited(e))
                throw new RateLimitException();
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    public void delete(String key) {
        try {
            PresignedUrl delUrl = S3AdminRequests.preSignDelete(folder + key, Optional.empty(),
                    S3AdminRequests.asAwsDate(ZonedDateTime.now()), host, region, Optional.empty(), accessKeyId, secretKey, useHttps, hasher).join();
            HttpUtil.delete(delUrl);
        } catch (Exception e) {
            // Some stores answer a delete of an absent key with a 404 rather than a 204
            String msg = e.getMessage();
            if (msg != null && (msg.startsWith("HTTP 404") || msg.contains("<Code>NoSuchKey</Code>")))
                return;
            throw new RuntimeException(e);
        }
    }

    public void bulkDelete(List<String> keys) {
        if (keys.isEmpty())
            return;
        List<Pair<String, String>> keyVersions = keys.stream()
                .map(k -> new Pair<>(folder + k, (String) null))
                .toList();
        try {
            S3AdminRequests.bulkDelete(keyVersions, ZonedDateTime.now(), host, region, Optional.empty(), accessKeyId, secretKey,
                    b -> ArrayOps.bytesToHex(Hash.sha256(b)),
                    b -> Base64.encodeBase64String(Hash.sha256(b)),
                    (url, body) -> {
                        try {
                            return HttpUtil.post(url, body);
                        } catch (IOException e) {
                            if (S3BlockStorage.isRateLimitedException(e))
                                throw new RateLimitException();
                            throw new RuntimeException(e);
                        }
                    }, S3AdminRequests.builder::get, useHttps, hasher);
        } catch (Exception e) {
            // B2 doesn't implement bulk delete
            int failures = 0;
            for (String key : keys) {
                try {
                    delete(key);
                } catch (Exception f) {
                    failures++;
                    LOG.log(Level.WARNING, "S3 cbor mirror couldn't delete " + key, f);
                }
            }
            if (failures > 0)
                LOG.warning("S3 cbor mirror failed to delete " + failures + " of " + keys.size() + " blocks");
        }
    }

    @Override
    public String toString() {
        return "S3CborMirror[" + bucket + ":" + folder + "]";
    }
}
