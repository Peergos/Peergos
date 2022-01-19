package peergos.server.storage;

import peergos.server.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.storage.*;
import peergos.shared.storage.auth.*;

import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.util.logging.*;

public class S3DeleteOld {

    private static final Logger LOG = Logger.getGlobal();

    private static void applyToAllInRange(Consumer<S3AdminRequests.ObjectMetadata> processor,
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
                        }, S3AdminRequests.builder::get, h);

                for (S3AdminRequests.ObjectMetadata objectSummary : result.objects) {
                    if (objectSummary.key.endsWith("/")) {
                        LOG.fine(" - " + objectSummary.key + "  " + "(directory)");
                        continue;
                    }
                    if (endPrefix.isPresent() && objectSummary.key.compareTo(endPrefix.get()) >= 0)
                        return;
                    processor.accept(objectSummary);
                }
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
                                     Consumer<S3AdminRequests.ObjectMetadata> processor,
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
                doneCounter.incrementAndGet();
                pool.submit(() -> processor.accept(obj));
        }, startPrefix, endPrefix, conmfig, counter, h);
        System.out.println("Objects processed: " + doneCounter.get());
    }

    public static void main(String[] args) {
        Crypto crypto = Main.initJavaCrypto();
        Args a = Args.parse(args);
        S3Config config = S3Config.build(a);

        String startPrefix = "";
        Optional<String> endPrefix = Optional.empty();
        LocalDateTime cutoff = LocalDate.parse(a.getArg("delete-before-date")).atStartOfDay();
        String host = config.getHost();

        Consumer<S3AdminRequests.ObjectMetadata> processor = m -> {
            try {
                if (m.lastModified.isBefore(cutoff)) {
                    PresignedUrl delUrl = S3Request.preSignDelete(m.key, S3AdminRequests.asAwsDate(ZonedDateTime.now()), host,
                            config.region, config.accessKey, config.secretKey, crypto.hasher).join();
                    HttpUtil.delete(delUrl);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };

        System.out.println("Deleting objects in S3 bucket " + config.bucket + " older than " + cutoff);
        applyToRange(startPrefix, endPrefix, processor, config, new AtomicLong(0),
                new AtomicLong(0), a.getInt("parallelism"), Main.initCrypto().hasher);
    }
}
