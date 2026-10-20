package peergos.server.storage;

import io.prometheus.client.Counter;
import peergos.server.util.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.bases.Base64;
import peergos.shared.storage.*;
import peergos.shared.storage.auth.*;
import peergos.shared.util.*;

import java.io.*;
import java.time.*;
import java.util.*;
import java.util.logging.*;

/** A second S3 bucket holding a copy of every cbor block in an S3BlockStorage, under the same keys.
 *  It is only ever a cache: anything missing here is read from the main store.
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

    private final String region, bucket, folder, host;
    private final boolean useHttps;
    private final String accessKeyId, secretKey;
    private final Hasher hasher;

    public S3CborMirror(S3Config config, Hasher hasher) {
        this.region = config.region;
        this.bucket = config.bucket;
        this.host = config.getHost();
        this.useHttps = ! host.endsWith("localhost") && ! host.contains("localhost:");
        this.folder = (useHttps ? "" : bucket + "/") + (config.path.isEmpty() || config.path.endsWith("/") ? config.path : config.path + "/");
        this.accessKeyId = config.accessKey;
        this.secretKey = config.secretKey;
        this.hasher = hasher;
        LOG.info("Mirroring cbor blocks to S3 at " + config.regionEndpoint + ", bucket " + config.bucket + ", path: " + config.path);
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
        } catch (IOException e) {
            if (S3BlockStorage.isRateLimitedException(e))
                throw new RateLimitException();
            String msg = e.getMessage();
            if (msg != null && msg.contains("<Code>NoSuchKey</Code>"))
                return false;
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
        } catch (IOException e) {
            if (S3BlockStorage.isRateLimitedException(e))
                throw new RateLimitException();
            LOG.log(Level.SEVERE, "S3 cbor mirror error writing " + key + ": " + e.getMessage(), e);
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    public void delete(String key) {
        try {
            PresignedUrl delUrl = S3AdminRequests.preSignDelete(folder + key, Optional.empty(),
                    S3AdminRequests.asAwsDate(ZonedDateTime.now()), host, region, Optional.empty(), accessKeyId, secretKey, useHttps, hasher).join();
            HttpUtil.delete(delUrl);
        } catch (Exception e) {
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
            for (String key : keys)
                delete(key);
        }
    }

    @Override
    public String toString() {
        return "S3CborMirror[" + bucket + ":" + folder + "]";
    }
}
