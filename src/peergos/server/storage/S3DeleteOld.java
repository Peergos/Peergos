package peergos.server.storage;

import peergos.server.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.storage.*;
import peergos.shared.util.*;

import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.util.logging.*;

public class S3DeleteOld {

    private static final Logger LOG = Logger.getGlobal();

    private static void applyToAllInRange(Consumer<List<S3AdminRequests.ObjectMetadata>> processor,
                                          String startPrefix,
                                          Optional<String> endPrefix,
                                          S3Config config,
                                          AtomicLong counter,
                                          Hasher h) {
        try {
            Optional<String> continuationToken = Optional.empty();
            S3AdminRequests.ListObjectsReply result;
            do {
                result = S3AdminRequests.listObjects(startPrefix, 1_000, continuationToken,
                        ZonedDateTime.now(), config.getHost(), config.region, config.accessKey, config.secretKey, url -> {
                            try {
                                return HttpUtil.get(url);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        }, S3AdminRequests.builder::get, true, h);

                List<S3AdminRequests.ObjectMetadata> toProcess = new ArrayList<>();
                for (S3AdminRequests.ObjectMetadata objectSummary : result.objects) {
                    if (objectSummary.key.endsWith("/")) {
                        LOG.fine(" - " + objectSummary.key + "  " + "(directory)");
                        continue;
                    }
                    if (endPrefix.isPresent() && objectSummary.key.compareTo(endPrefix.get()) >= 0)
                        return;
                    toProcess.add(objectSummary);
                }
                processor.accept(toProcess);
                long done = counter.addAndGet(result.objects.size());
                if ((done / 1000) % 10 == 0)
                    System.out.println("Objects processed: " + done);
                LOG.log(Level.FINE, "Next Continuation Token : " + result.continuationToken);
                continuationToken = result.continuationToken;
            } while (result.isTruncated);

        } catch (Exception e) {
            LOG.log(Level.SEVERE, e.getMessage(), e);
        }
    }

    private static void applyToRange(String startPrefix,
                                     Optional<String> endPrefix,
                                     Consumer<List<S3AdminRequests.ObjectMetadata>> processor,
                                     S3Config conmfig,
                                     AtomicLong counter,
                                     AtomicLong doneCounter,
                                     int parallelism,
                                     Hasher h) {
        ForkJoinPool pool = new ForkJoinPool(parallelism);
        System.out.println("Processing objects...");
        applyToAllInRange(obj -> {
                while (pool.getQueuedSubmissionCount() > 100)
                    try {Thread.sleep(100);} catch (InterruptedException e) {}
                doneCounter.addAndGet(obj.size());
                pool.submit(() -> processor.accept(obj));
        }, startPrefix, endPrefix, conmfig, counter, h);
        while (! pool.isQuiescent())
            try {Thread.sleep(100);} catch (InterruptedException e) {}
        System.out.println("Objects processed: " + doneCounter.get());
    }

    public static void bulkDelete(List<String> keys, S3Config config, Hasher hasher) {
        S3AdminRequests.bulkDelete(keys, ZonedDateTime.now(), config.getHost(), config.region, config.accessKey, config.secretKey,
                b -> ArrayOps.bytesToHex(Hash.sha256(b)),
                (url, body) -> {
                    try {
                        return HttpUtil.post(url, body);
                    } catch (IOException e) {
                        String msg = e.getMessage();
                        boolean rateLimited = msg.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error><Code>SlowDown</Code>");
                        if (rateLimited) {
                            throw new RateLimitException();
                        }
                        throw new RuntimeException(e);
                    }
                }, S3AdminRequests.builder::get, true, hasher);
    }

    public static void main(String[] args) {
        Crypto crypto = Main.initJavaCrypto();
        Args a = Args.parse(args);
        S3Config config = S3Config.build(a);

        String startPrefix = "";
        Optional<String> endPrefix = Optional.empty();
        LocalDateTime cutoff = LocalDate.parse(a.getArg("delete-before-date")).atStartOfDay();

        Consumer<List<S3AdminRequests.ObjectMetadata>> processor = objs -> {
            List<String> toDelete = new ArrayList<>();
            for (S3AdminRequests.ObjectMetadata m : objs) {
                try {
                    if (m.lastModified.isBefore(cutoff)) {
                        System.out.println("Deleting " + m.key);
                        toDelete.add(m.key);
                        if (toDelete.size() > 1_000) {
                            bulkDelete(toDelete, config, crypto.hasher);
                            toDelete.clear();
                        }
                    }
                } catch (Exception e) {
                    System.err.println(e.getMessage());
                }
            }
            if (! toDelete.isEmpty())
                bulkDelete(toDelete, config, crypto.hasher);
        };

        System.out.println("Deleting objects in S3 bucket " + config.bucket + " older than " + cutoff);
        applyToRange(startPrefix, endPrefix, processor, config, new AtomicLong(0),
                new AtomicLong(0), a.getInt("parallelism"), Main.initCrypto().hasher);
    }
}
