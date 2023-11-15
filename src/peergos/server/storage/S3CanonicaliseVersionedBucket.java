package peergos.server.storage;

import peergos.server.*;
import peergos.server.util.*;
import peergos.shared.crypto.hash.*;

import java.io.*;
import java.time.*;
import java.util.*;
import java.util.function.*;
import java.util.logging.*;

public class S3CanonicaliseVersionedBucket {

    private static final Logger LOG = Logger.getGlobal();

    private static void applyToAllVersions(String prefix,
                                           Consumer<S3AdminRequests.ObjectMetadataVersion> processor,
                                           Consumer<S3AdminRequests.DeleteMarker> deleteProcessor,
                                           long maxObjects,
                                           S3Config config,
                                           Hasher h) {
        try {
            Optional<String> keyMarker = Optional.empty();
            Optional<String> versionIdMarker = Optional.empty();
            S3AdminRequests.ListObjectVersionsReply result;
            long processedObjects = 0;
            do {
                result = S3AdminRequests.listObjectVersions(prefix, 1_000, keyMarker, versionIdMarker,
                        ZonedDateTime.now(), config.getHost(), config.region, config.accessKey, config.secretKey, url -> {
                            try {
                                return HttpUtil.get(url);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        }, S3AdminRequests.builder::get, true, h);

                for (S3AdminRequests.ObjectMetadataVersion objectSummary : result.versions) {
                    if (objectSummary.key.endsWith("/")) {
                        LOG.fine(" - " + objectSummary.key + "  " + "(directory)");
                        continue;
                    }
                    processor.accept(objectSummary);
                    processedObjects++;
                    if (processedObjects >= maxObjects)
                        return;
                }
                for (S3AdminRequests.DeleteMarker deleteSummary : result.deletes) {
                    if (deleteSummary.key.endsWith("/")) {
                        LOG.fine(" - " + deleteSummary.key + "  " + "(directory)");
                        continue;
                    }
                    deleteProcessor.accept(deleteSummary);
                    processedObjects++;
                    if (processedObjects >= maxObjects)
                        return;
                }
                LOG.log(Level.FINE, "Next key marker : " + result.nextKeyMarker);
                LOG.log(Level.FINE, "Next version id marker : " + result.nextVersionIdMarker);
                keyMarker = result.nextKeyMarker;
                versionIdMarker = result.nextVersionIdMarker;
            } while (result.isTruncated);

        } catch (Exception e) {
            LOG.log(Level.SEVERE, e.getMessage(), e);
        }
    }

    private static void processFileVersions(long maxReturned, S3Config config, Hasher h) {
        applyToAllVersions("", obj -> {
            try {
                if (! obj.isLatest) {
                    System.out.println("Old version of " + obj.key);
                    // TODO delete
                }
            } catch (Exception e) {
                LOG.warning("Couldn't parse S3 key to Cid: " + obj.key);
            }
        }, del -> {
            try {
                if (! del.isLatest) {
                    System.out.println("Old delete marker version of " + del.key);
                    // TODO delete
                }
            } catch (Exception e) {
                LOG.warning("Couldn't parse S3 key to Cid: " + del.key);
            }
        }, maxReturned, config, h);
    }

    public static void main(String[] args) {
        Args a = Args.parse(args);
        S3Config config = S3Config.build(a, Optional.empty());

        System.out.println("Listing old versions in S3 bucket " + config.getHost() + "/" + config.bucket);
        processFileVersions(Long.MAX_VALUE, config, Main.initCrypto().hasher);
    }
}
