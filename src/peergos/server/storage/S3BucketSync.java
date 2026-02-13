package peergos.server.storage;

import peergos.server.Main;
import peergos.server.util.Args;
import peergos.server.util.HttpUtil;
import peergos.server.util.Threads;
import peergos.shared.crypto.hash.Hasher;
import peergos.shared.storage.PresignedUrl;
import peergos.shared.storage.RateLimitException;
import peergos.shared.storage.auth.S3Request;
import peergos.shared.util.ArrayOps;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public class S3BucketSync {

    private static final Logger LOG = Logger.getGlobal();

    private static void applyToAllInRange(Consumer<S3AdminRequests.ObjectMetadata> processor,
                                          String startPrefix,
                                          Optional<String> endPrefix,
                                          S3Config config,
                                          AtomicLong counter,
                                          Hasher h) {
        Optional<String> continuationToken = Optional.empty();
        S3AdminRequests.ListObjectsReply result;
        while (true) {
            try {
                result = S3AdminRequests.listObjects(startPrefix, 1_000, continuationToken,
                        ZonedDateTime.now(), config.getHost(), config.region, config.storageClass, config.accessKey, config.secretKey, url -> {
                            try {
                                return HttpUtil.get(url);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        }, S3AdminRequests.builder::get, true, h);

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
                if (! result.isTruncated)
                    break;
            } catch (RateLimitException r) {
                Threads.sleep(5_000);
            } catch (Exception e) {
                LOG.log(Level.SEVERE, e.getMessage(), e);
            }
        }
    }

    private static Map<String, String> getFileHashes(S3Config config, Hasher h) {
        Map<String, String> results = new HashMap<>();
        applyToAllInRange(obj -> {
            results.put(obj.key, obj.etag.substring(1, obj.etag.length() - 1)); // strip "'s
        }, "", Optional.empty(), config, new AtomicLong(0), h);
        return results;
    }

    private static void uploadFile(String key,
                                   Path source,
                                   S3Config target,
                                   Hasher h) {
        try {
            System.out.println("Copying " + source + " to s3://" + target.getHost() + "/" + key);
            byte[] res = Files.readAllBytes(source);
            Map<String, String> extraHeaders = new TreeMap<>();
            boolean hashContent = true;
            String contentHash = hashContent ? ArrayOps.bytesToHex(h.sha256(res).join()) : "UNSIGNED-PAYLOAD";
            HttpUtil.putWithVersion(S3Request.preSignPut(key, res.length, contentHash, target.storageClass, false,
                    S3AdminRequests.asAwsDate(ZonedDateTime.now()), target.getHost(), extraHeaders, target.region, target.accessKey, target.secretKey,  true,h).join(), res);
        } catch (IOException e) {
            e.printStackTrace();
            System.err.println(e.getMessage());
        }
    }

    private static String md5(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");

            try (InputStream is = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int bytesRead;

                while ((bytesRead = is.read(buffer)) != -1) {
                    md.update(buffer, 0, bytesRead);
                }
            }

            return ArrayOps.bytesToHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private static void syncFrom(String startPrefix,
                                 Optional<String> endPrefix,
                                 Path source,
                                 S3Config dest,
                                 AtomicLong counter,
                                 AtomicLong copyCounter,
                                 int parallelism,
                                 Hasher h) throws Exception {
        System.out.println("Listing destination bucket...");
        Map<String, String> targetKeys = getFileHashes(dest, h);
        AtomicInteger skipped = new AtomicInteger(0);
        Set<String> done = new HashSet<>();
        ForkJoinPool pool = Threads.newPool(parallelism, "S3-copy-");
        System.out.println("Syncing objects...");
        Files.walkFileTree(source, new FileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes basicFileAttributes) throws IOException {
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes basicFileAttributes) throws IOException {
                if (basicFileAttributes.isDirectory())
                    return FileVisitResult.CONTINUE;
                String filepath = source.relativize(file).toString();
                String localMd5 = md5(file);
                String remoteMd5 = targetKeys.get(filepath);
                if (localMd5.equals(remoteMd5)) {
                    done.add(filepath);
                    skipped.incrementAndGet();
                    return FileVisitResult.CONTINUE;
                }
                System.out.println("Remote md5: " + remoteMd5 + ", local: " + localMd5);
                while (pool.getQueuedSubmissionCount() > 100)
                    try {Thread.sleep(100);} catch (InterruptedException e) {}
                copyCounter.incrementAndGet();
                pool.submit(() -> {
                    System.out.println("Uploading " + filepath);
                    uploadFile(filepath, file, dest, h);
                });
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path path, IOException e) throws IOException {
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path path, IOException e) throws IOException {
                return FileVisitResult.CONTINUE;
            }
        });
        HashSet<String> toDelete = new HashSet<>(targetKeys.keySet());
        toDelete.removeAll(done);
        System.out.println("Deleting " + toDelete.size() + " remote files...");
        for (String key : toDelete) {
            PresignedUrl delUrl = S3AdminRequests.preSignDelete(key, Optional.empty(),
                    S3AdminRequests.asAwsDate(ZonedDateTime.now()), dest.getHost(), dest.region, dest.storageClass,
                    dest.accessKey, dest.secretKey, true, h).join();
            HttpUtil.delete(delUrl);
        }

        while (! pool.isQuiescent())
            try {Thread.sleep(100);} catch (InterruptedException e) {}
        System.out.println("Objects copied: " + copyCounter.get());
        System.out.println("Objects skipped: " + skipped.get());
    }

    public static void main(String[] args) throws Exception {
        Args a = Args.parse(args);
        S3Config dest = S3Config.build(a, Optional.empty());
        Path source = Paths.get(a.getArg("source-dir"));

        String startPrefix = "";
        Optional<String> endPrefix = Optional.empty();

        System.out.println("Sync S3 bucket " + dest.getHost() + "/" + dest.bucket + " from " + source);
        syncFrom(startPrefix, endPrefix, source, dest, new AtomicLong(0),
                new AtomicLong(0), a.getInt("parallelism"), Main.initCrypto().hasher);
    }
}
