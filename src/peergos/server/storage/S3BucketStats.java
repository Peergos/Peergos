package peergos.server.storage;

import peergos.server.*;
import peergos.server.util.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.storage.*;

import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.util.logging.*;

public class S3BucketStats {

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
            } while (result.isTruncated);

        } catch (Exception e) {
            LOG.log(Level.SEVERE, e.getMessage(), e);
        }
    }

    private static void analyseRange(String startPrefix,
                                     Optional<String> endPrefix,
                                     S3Config source,
                                     AtomicLong counter,
                                     Hasher h) {
        AtomicLong rawBlocks = new AtomicLong(0);
        AtomicLong cborBlocks = new AtomicLong(0);
        AtomicLong cborBlocksSize = new AtomicLong(0);
        AtomicLong rawBlocksSize = new AtomicLong(0);
        applyToAllInRange(obj -> {
            boolean isRaw = DirectS3BlockStore.keyToHash(obj.key).isRaw();
            if (isRaw) {
                rawBlocks.incrementAndGet();
                rawBlocksSize.addAndGet(obj.size);
            } else {
                cborBlocks.incrementAndGet();
                cborBlocksSize.addAndGet(obj.size);
            }
        }, startPrefix, endPrefix, source, counter, h);
        System.out.println("Raw blocks: " + rawBlocks.get() + ",  size: " + rawBlocksSize.get() + ",  average size: " + (rawBlocksSize.get()/rawBlocks.get()));
        System.out.println("Cbor blocks: " + cborBlocks.get() + ",  size: " + cborBlocksSize.get() + ",  average size: " + (cborBlocksSize.get()/cborBlocks.get()));
    }
    public static void main(String[] args) {
        Args a = Args.parse(args);
        S3Config source = S3Config.build(a, Optional.empty());

        String startPrefix = "";
        Optional<String> endPrefix = Optional.empty();

        System.out.println("Analysing S3 bucket " + source.getHost() + "/" + source.bucket);
        analyseRange(startPrefix, endPrefix, source, new AtomicLong(0), Main.initCrypto().hasher);
    }
}
