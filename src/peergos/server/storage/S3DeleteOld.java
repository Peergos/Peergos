package peergos.server.storage;

import peergos.server.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.storage.*;
import peergos.shared.storage.auth.*;
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

    private static void applyToAllInRange(Consumer<List<S3AdminRequests.ObjectMetadataVersion>> processor,
                                          Consumer<List<S3AdminRequests.DeleteMarker>> deleteProcessor,
                                          String startPrefix,
                                          Optional<String> endPrefix,
                                          S3Config config,
                                          AtomicLong counter,
                                          Hasher h) {
        try {
            Optional<String> keyMarker = Optional.empty();
            Optional<String> versionIdMarker = Optional.empty();
            S3AdminRequests.ListObjectVersionsReply result;
            do {
                result = S3AdminRequests.listObjectVersions(startPrefix, 1_000, keyMarker, versionIdMarker,
                        ZonedDateTime.now(), config.getHost(), config.region, config.accessKey, config.secretKey, url -> {
                            try {
                                return HttpUtil.get(url);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        }, S3AdminRequests.builder::get, true, h);

                List<S3AdminRequests.ObjectMetadataVersion> toProcess = new ArrayList<>();
                List<S3AdminRequests.DeleteMarker> deletesToProcess = new ArrayList<>();
                for (S3AdminRequests.ObjectMetadataVersion objectSummary : result.versions) {
                    if (objectSummary.key.endsWith("/")) {
                        LOG.fine(" - " + objectSummary.key + "  " + "(directory)");
                        continue;
                    }
                    if (endPrefix.isPresent() && objectSummary.key.compareTo(endPrefix.get()) >= 0)
                        return;
                    toProcess.add(objectSummary);
                }
                for (S3AdminRequests.DeleteMarker deleteSummary : result.deletes) {
                    if (deleteSummary.key.endsWith("/")) {
                        LOG.fine(" - " + deleteSummary.key + "  " + "(directory)");
                        continue;
                    }
                    if (endPrefix.isPresent() && deleteSummary.key.compareTo(endPrefix.get()) >= 0)
                        return;
                    deletesToProcess.add(deleteSummary);
                }
                processor.accept(toProcess);
                deleteProcessor.accept(deletesToProcess);
                long done = counter.addAndGet(result.versions.size());
                if ((done / 1000) % 10 == 0)
                    System.out.println("Objects processed: " + done);
                LOG.log(Level.FINE, "Next Key Marker : " + result.nextKeyMarker);
                LOG.log(Level.FINE, "Next Version Id Marker : " + result.nextVersionIdMarker);
                keyMarker = result.nextKeyMarker;
                versionIdMarker = result.nextVersionIdMarker;
            } while (result.isTruncated);

        } catch (Exception e) {
            LOG.log(Level.SEVERE, e.getMessage(), e);
        }
    }

    private static void applyToRange(String startPrefix,
                                     Optional<String> endPrefix,
                                     Consumer<List<S3AdminRequests.ObjectMetadataVersion>> processor,
                                     Consumer<List<S3AdminRequests.DeleteMarker>> deleteProcessor,
                                     S3Config config,
                                     AtomicLong counter,
                                     AtomicLong doneCounter,
                                     int parallelism,
                                     Hasher h) {
        ForkJoinPool pool = Threads.newPool(parallelism, "S3-delete-");
        System.out.println("Processing objects...");
        applyToAllInRange(obj -> {
                while (pool.getQueuedSubmissionCount() > 100)
                    try {Thread.sleep(100);} catch (InterruptedException e) {}
                doneCounter.addAndGet(obj.size());
                pool.submit(() -> processor.accept(obj));
        }, del -> {
                while (pool.getQueuedSubmissionCount() > 100)
                    try {Thread.sleep(100);} catch (InterruptedException e) {}
                doneCounter.addAndGet(del.size());
                pool.submit(() -> deleteProcessor.accept(del));
        }, startPrefix, endPrefix, config, counter, h);
        while (! pool.isQuiescent())
            try {Thread.sleep(100);} catch (InterruptedException e) {}
        System.out.println("Objects processed: " + doneCounter.get());
    }

    public static void delete(Pair<String, String> version, S3Config config, Hasher hasher) {
        try {
            PresignedUrl delUrl = S3AdminRequests.preSignDelete(version.left, Optional.ofNullable(version.right),
                    S3AdminRequests.asAwsDate(ZonedDateTime.now()), config.getHost(), config.region, config.accessKey,
                    config.secretKey, true, hasher).join();
            HttpUtil.delete(delUrl);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void bulkDelete(List<Pair<String, String>> keyVersions, S3Config config, Hasher hasher) {
        try {
            S3AdminRequests.bulkDelete(keyVersions, ZonedDateTime.now(), config.getHost(), config.region, config.accessKey, config.secretKey,
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
        } catch (Exception e) {
            // fallback to doing deletes with parallel single calls
            // This is necessary because B2 doesn't implement the bulk delete call!!
            System.out.println("Falling back to parallel individual block deletes...");
            for (Pair<String, String> version : keyVersions) {
                new Thread(() -> delete(version, config, hasher)).start();
            }
        }
    }

    public static void main(String[] args) {
        Crypto crypto = JavaCrypto.init();
        Args a = Args.parse(args);
        S3Config config = S3Config.build(a, Optional.empty());

        String startPrefix = "";
        Optional<String> endPrefix = Optional.empty();
        LocalDateTime cutoff = LocalDate.parse(a.getArg("delete-before-date")).atStartOfDay();

        Consumer<List<S3AdminRequests.ObjectMetadataVersion>> processor = objs -> {
            List<Pair<String, String>> toDelete = new ArrayList<>();
            for (S3AdminRequests.ObjectMetadataVersion m : objs) {
                try {
                    if (m.lastModified.isBefore(cutoff)) {
                        System.out.println("Deleting " + m.key);
                        toDelete.add(new Pair<>(m.key, m.version));
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
        Consumer<List<S3AdminRequests.DeleteMarker>> deleteProcessor = dels -> {
            List<Pair<String, String>> toDelete = new ArrayList<>();
            for (S3AdminRequests.DeleteMarker m : dels) {
                try {
                    if (m.lastModified.isBefore(cutoff)) {
                        System.out.println("Deleting " + m.key);
                        toDelete.add(new Pair<>(m.key, m.version));
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
        applyToRange(startPrefix, endPrefix, processor, deleteProcessor, config, new AtomicLong(0),
                new AtomicLong(0), a.getInt("parallelism"), Main.initCrypto().hasher);
    }
}
