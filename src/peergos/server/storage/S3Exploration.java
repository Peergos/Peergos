package peergos.server.storage;

import org.junit.*;
import peergos.server.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.storage.*;
import peergos.shared.storage.auth.*;
import peergos.shared.util.*;

import java.io.*;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.stream.*;

class S3Exploration {
    public static void main(String[] a) throws Exception {
        testVersionedBucket(a);
    }

    public static void testVersionedBucket(String[] a) throws Exception {
        Crypto crypto = Main.initCrypto();
        String accessKey = a[0];
        String secretKey = a[1];
        String bucketName = a[2];
        String region = "us-east-1";
        String regionEndpoint = "s3." + region + ".linodeobjects.com";

        String host = bucketName + "." + regionEndpoint;

        byte[] payload = new byte[4096];
        new Random(1).nextBytes(payload);
        Hasher h = crypto.hasher;
        RAMStorage ram = new RAMStorage(h);
        TransactionId tid = ram.startTransaction(null).join();
        Multihash content = ram.put(null, null, null, Collections.singletonList(payload), tid).join().get(0);
        String s3Key = DirectS3BlockStore.hashToKey(content);// "AFYREIBF5Y4OUJXNGRCHBAR2ZMPQBSW62SZDHFNX2GA6V4J3W7I63LA4UQ"
        boolean useHttps = true;

        // test an authed PUT
        Map<String, String> extraHeaders = new TreeMap<>();
        extraHeaders.put("Content-Type", "application/octet-stream");
        extraHeaders.put("User-Agent", "Bond, James Bond");
        boolean useIllegalPayload = false;
        boolean hashContent = true;
        String contentHash = hashContent ? ArrayOps.bytesToHex(content.getHash()) : "UNSIGNED-PAYLOAD";
        PresignedUrl putUrl = S3Request.preSignPut(s3Key, payload.length, contentHash, false,
                S3AdminRequests.asAwsDate(ZonedDateTime.now().minusMinutes(14)), host, extraHeaders, region, accessKey, secretKey, useHttps, h).join();
        // put same object twice to create two identical versions
        new String(write(new URI(putUrl.base).toURL(), "PUT", putUrl.fields, useIllegalPayload ? new byte[payload.length] : payload));
        new String(write(new URI(putUrl.base).toURL(), "PUT", putUrl.fields, useIllegalPayload ? new byte[payload.length] : payload));

        // list bucket to get all files and latest versions
        S3AdminRequests.ListObjectVersionsReply listing = S3AdminRequests.listObjectVersions(s3Key, 20, Optional.empty(),
                Optional.empty(), ZonedDateTime.now(), host, region, accessKey, secretKey, url -> get(url),
                S3AdminRequests.builder::get, useHttps, h);
        System.out.println();

        // do a normal delete (adds a delete marker, leaving old versions)
        PresignedUrl delUrl = S3AdminRequests.preSignDelete(s3Key, Optional.empty(), S3AdminRequests.asAwsDate(ZonedDateTime.now()), host, region, accessKey, secretKey, useHttps, h).join();
        delete(new URI(delUrl.base).toURL(), delUrl.fields);

        // check versions include delete version
        S3AdminRequests.ListObjectVersionsReply listing2 = S3AdminRequests.listObjectVersions(s3Key, 20, Optional.empty(),
                Optional.empty(), ZonedDateTime.now(), host, region, accessKey, secretKey, url -> get(url),
                S3AdminRequests.builder::get, useHttps, h);
        if (listing2.deletes.size() != listing.deletes.size() + 1)
            throw new IllegalStateException("Where's delete?");

        List<Pair<String, String>> versionsToDelete = new ArrayList<>();
        versionsToDelete.addAll(listing2.versions.stream()
                .filter(m -> m.key.equals(s3Key))
                .map(m -> new Pair<>(m.key, m.version))
                .collect(Collectors.toList()));
        versionsToDelete.addAll(listing2.deletes.stream()
                .filter(m -> m.key.equals(s3Key))
                .map(m -> new Pair<>(m.key, m.version))
                .collect(Collectors.toList()));
        // delete all versions of the key and delete markers using a bulk delete call
        S3AdminRequests.BulkDeleteReply bulkDelete = S3AdminRequests.bulkDelete(
                versionsToDelete, ZonedDateTime.now(), host, region, accessKey, secretKey, b -> ArrayOps.bytesToHex(Hash.sha256(b)),
                (url, body) -> {
                    try {
                        System.out.println("URL: " + url.base);
                        url.fields.entrySet().forEach(e -> System.out.println("HEADER: " + e.getKey() + ": " + e.getValue()));
                        System.out.println("BODY: " + new String(body));
                        return write(toURL(url.base), "POST", url.fields, body);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }, S3AdminRequests.builder::get, useHttps, h);

        S3AdminRequests.ListObjectVersionsReply afterDelete = S3AdminRequests.listObjectVersions(s3Key, 20, Optional.empty(),
                Optional.empty(), ZonedDateTime.now(), host, region, accessKey, secretKey, url -> get(url),
                S3AdminRequests.builder::get, useHttps, h);
        if (afterDelete.versions.stream().anyMatch(m -> m.key.equals(s3Key)) || afterDelete.deletes.stream().anyMatch(d -> d.key.equals(s3Key)))
            throw new IllegalStateException("Bulk delete failed");

        // delete all versions and delete markers of the key
//        for (S3AdminRequests.ObjectMetadataVersion version : listing2.versions) {
//            if (version.key.equals(s3Key)) {
//                PresignedUrl delUrl2 = S3AdminRequests.preSignDelete(version.key, Optional.of(version.version), S3AdminRequests.asAwsDate(ZonedDateTime.now()), host, region, accessKey, secretKey, useHttps, h).join();
//                delete(new URI(delUrl2.base).toURL(), delUrl2.fields);
//            }
//        }
//        for (S3AdminRequests.DeleteMarker delete : listing2.deletes) {
//            if (delete.key.equals(s3Key)) {
//                PresignedUrl delUrl2 = S3AdminRequests.preSignDelete(delete.key, Optional.of(delete.version), S3AdminRequests.asAwsDate(ZonedDateTime.now()), host, region, accessKey, secretKey, useHttps, h).join();
//                delete(new URI(delUrl2.base).toURL(), delUrl2.fields);
//            }
//        }
//
//        // check bucket is empty
//        S3AdminRequests.ListObjectVersionsReply listing3 = S3AdminRequests.listObjectVersions("", 20, Optional.empty(),
//                Optional.empty(), ZonedDateTime.now(), host, region, accessKey, secretKey, url -> get(url),
//                S3AdminRequests.builder::get, useHttps, h);
//        if (! listing3.versions.isEmpty() || ! listing3.deletes.isEmpty())
//            throw new IllegalStateException("Not all versions deleted!");
    }

    public static void explore(String[] a) throws Exception {
        Crypto crypto = Main.initCrypto();
        String accessKey = a[0];
        String secretKey = a[1];
        String bucketName = a[2];
        String region = "us-east-1";
        String regionEndpoint = region + ".linodeobjects.com";
        String host = bucketName + "." + regionEndpoint;

        byte[] payload = new byte[4096];
        new Random(1).nextBytes(payload);
        Hasher h = crypto.hasher;
        RAMStorage ram = new RAMStorage(h);
        TransactionId tid = ram.startTransaction(null).join();
        Multihash content = ram.put(null, null, null, Collections.singletonList(payload), tid).join().get(0);
        String s3Key = DirectS3BlockStore.hashToKey(content);// "AFYREIBF5Y4OUJXNGRCHBAR2ZMPQBSW62SZDHFNX2GA6V4J3W7I63LA4UQ"
        boolean useHttps = true;
        {
            // test an authed PUT
            Map<String, String> extraHeaders = new TreeMap<>();
            extraHeaders.put("Content-Type", "application/octet-stream");
            extraHeaders.put("User-Agent", "Bond, James Bond");
            boolean useIllegalPayload = false;
            boolean hashContent = true;
            String contentHash = hashContent ? ArrayOps.bytesToHex(content.getHash()) : "UNSIGNED-PAYLOAD";
            PresignedUrl putUrl = S3Request.preSignPut(s3Key, payload.length, contentHash, false,
                    S3AdminRequests.asAwsDate(ZonedDateTime.now().minusMinutes(14)), host, extraHeaders, region, accessKey, secretKey, useHttps, h).join();
            String putRes = new String(write(new URI(putUrl.base).toURL(), "PUT", putUrl.fields, useIllegalPayload ? new byte[payload.length] : payload));
            System.out.println(putRes);

            // test copying over to reset modified time
            PresignedUrl getaUrl = S3Request.preSignGet(s3Key, Optional.of(600), Optional.of(new Pair<>(0, Bat.MAX_RAW_BLOCK_PREFIX_SIZE - 1)),
                    S3AdminRequests.asAwsDate(ZonedDateTime.now()), host, region, accessKey, secretKey, useHttps, h).join();
            byte[] prefix = get(new URI(getaUrl.base).toURL(), getaUrl.fields);
            Assert.assertTrue(prefix.length == Bat.MAX_RAW_BLOCK_PREFIX_SIZE);
            String tempKey = s3Key + "Z";
            {
                PresignedUrl copyUrl = S3Request.preSignCopy(bucketName, s3Key, tempKey,
                        S3AdminRequests.asAwsDate(ZonedDateTime.now()), host, Collections.emptyMap(), region, accessKey, secretKey, useHttps, h).join();
                String res = new String(write(new URI(copyUrl.base).toURL(), "PUT", copyUrl.fields, new byte[0]));
                System.out.println(res);
            }
            {
                PresignedUrl copyUrl = S3Request.preSignCopy(bucketName, tempKey, s3Key,
                        S3AdminRequests.asAwsDate(ZonedDateTime.now()), host, Collections.emptyMap(), region, accessKey, secretKey, useHttps, h).join();
                String res = new String(write(new URI(copyUrl.base).toURL(), "PUT", copyUrl.fields, new byte[0]));
                System.out.println(res);
            }
            get(new URI(getaUrl.base).toURL(), getaUrl.fields);

            // test a delete
            PresignedUrl delUrl = S3AdminRequests.preSignDelete(tempKey, Optional.empty(), S3AdminRequests.asAwsDate(ZonedDateTime.now()), host, region, accessKey, secretKey, useHttps, h).join();
            delete(new URI(delUrl.base).toURL(), delUrl.fields);
            System.out.println();

            // test bulk delete of two copies
            {
                PresignedUrl copyUrl = S3Request.preSignCopy(bucketName, s3Key, tempKey,
                        S3AdminRequests.asAwsDate(ZonedDateTime.now()), host, Collections.emptyMap(), region, accessKey, secretKey, useHttps, h).join();
                String res = new String(write(new URI(copyUrl.base).toURL(), "PUT", copyUrl.fields, new byte[0]));
            }
            String tempKey2 = s3Key + "ZZ";
            {
                PresignedUrl copyUrl = S3Request.preSignCopy(bucketName, s3Key, tempKey2,
                        S3AdminRequests.asAwsDate(ZonedDateTime.now()), host, Collections.emptyMap(), region, accessKey, secretKey, useHttps, h).join();
                String res = new String(write(new URI(copyUrl.base).toURL(), "PUT", copyUrl.fields, new byte[0]));
                System.out.println(res);
            }
            String nonExistentKey = tempKey2 + "ZZ";
            S3AdminRequests.BulkDeleteReply bulkDelete = S3AdminRequests.bulkDelete(
                    Arrays.asList(new Pair<>(tempKey, null), new Pair<>(tempKey2, null), new Pair<>(nonExistentKey, null)),
                    ZonedDateTime.now(), host, region, accessKey, secretKey, b -> ArrayOps.bytesToHex(Hash.sha256(b)),
                    (url, body) -> {
                        try {
                            System.out.println("URL: " + url.base);
                            url.fields.entrySet().forEach(e -> System.out.println("HEADER: " + e.getKey() + ": " + e.getValue()));
                            System.out.println("BODY: " + new String(body));
                            return write(toURL(url.base), "POST", url.fields, body);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }, S3AdminRequests.builder::get, useHttps, h);
            if (! bulkDelete.deletedKeys.containsAll(Arrays.asList(tempKey, tempKey2)))
                throw new IllegalStateException("Delete failed");
        }

        // Test a list objects GET
        S3AdminRequests.ListObjectsReply listing = S3AdminRequests.listObjects("", 10, Optional.empty(),
                ZonedDateTime.now(), host, region, accessKey, secretKey, url -> get(url), S3AdminRequests.builder::get, useHttps, h);

        // Test a list objects GET continuation
        S3AdminRequests.ListObjectsReply listing2 = S3AdminRequests.listObjects("", 10, Optional.of(s3Key),
                ZonedDateTime.now(), host, region, accessKey, secretKey, url -> get(url), S3AdminRequests.builder::get, useHttps, h);
        if (listing2.objects.get(0).key.equals(listing.objects.get(0).key))
            throw new IllegalStateException("Incorrect listing!");

        // test an authed HEAD
        PresignedUrl headUrl = S3Request.preSignHead(s3Key, Optional.of(600), S3AdminRequests.asAwsDate(ZonedDateTime.now()), host, region, accessKey, secretKey, useHttps, h).join();
        Map<String, List<String>> headRes = HttpUtil.head(headUrl);
        int size = Integer.parseInt(headRes.get("Content-Length").get(0));
        if (size != payload.length)
            throw new IllegalStateException("Incorrect size: " + size);

        // test an authed read
        PresignedUrl getUrl = S3Request.preSignGet(s3Key, Optional.of(600), Optional.empty(), S3AdminRequests.asAwsDate(ZonedDateTime.now()), host, region, accessKey, secretKey, useHttps, h).join();
        byte[] authReadBytes = get(new URI(getUrl.base).toURL(), getUrl.fields);
        if (! Arrays.equals(authReadBytes, payload))
            throw new IllegalStateException("Incorrect contents: " + new String(authReadBytes));

        // test an authed read which has expired
        PresignedUrl failGetUrl = S3Request.preSignGet(s3Key, Optional.of(600), Optional.empty(), S3AdminRequests.asAwsDate(ZonedDateTime.now().minusMinutes(11)), host, region, accessKey, secretKey, useHttps, h).join();
        String failReadRes = new String(get(new URI(failGetUrl.base).toURL(), failGetUrl.fields));
        System.out.println(failReadRes);

        // test a public read
        String webUrl = "https://" + bucketName + ".website-" + regionEndpoint + "/" + s3Key;
        byte[] getResult = get(new URI(webUrl).toURL(), Collections.emptyMap());
        if (! Arrays.equals(getResult, payload))
            System.out.println("Incorrect contents!");
    }

    private static URL toURL(String url) {
        try {
            return new URI(url).toURL();
        } catch (URISyntaxException | MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] write(URL target, String method, Map<String, String> headers, byte[] body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) target.openConnection();
        conn.setRequestMethod(method);
        for (Map.Entry<String, String> e : headers.entrySet()) {
            conn.setRequestProperty(e.getKey(), e.getValue());
        }
        conn.setDoOutput(true);
        OutputStream out = conn.getOutputStream();
        out.write(body);
        out.flush();
        out.close();

        try {
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream resp = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int r;
            while ((r = in.read(buf)) >= 0)
                resp.write(buf, 0, r);
            return resp.toByteArray();
        } catch (IOException e) {
            InputStream err = conn.getErrorStream();
            ByteArrayOutputStream resp = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int r;
            while ((r = err.read(buf)) >= 0)
                resp.write(buf, 0, r);
            throw new IOException("HTTP " + conn.getResponseCode() + ": " + conn.getResponseMessage() + "\nbody:\n" + new String(resp.toByteArray()));
        }
    }

    private static byte[] get(PresignedUrl url) {
        try {
            return get(new URI(url.base).toURL(), url.fields);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] get(URL target, Map<String, String> headers) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) target.openConnection();
        conn.setRequestMethod("GET");
        for (Map.Entry<String, String> e : headers.entrySet()) {
            conn.setRequestProperty(e.getKey(), e.getValue());
        }

        try {
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream resp = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int r;
            while ((r = in.read(buf)) >= 0)
                resp.write(buf, 0, r);
            return resp.toByteArray();
        } catch (IOException e) {
            InputStream err = conn.getErrorStream();
            ByteArrayOutputStream resp = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int r;
            while ((r = err.read(buf)) >= 0)
                resp.write(buf, 0, r);
            return resp.toByteArray();
        }
    }

    private static void delete(URL target, Map<String, String> headers) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) target.openConnection();
        conn.setRequestMethod("DELETE");
        for (Map.Entry<String, String> e : headers.entrySet()) {
            conn.setRequestProperty(e.getKey(), e.getValue());
        }

        try {
            int code = conn.getResponseCode();
            if (code == 204)
                return;
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream resp = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int r;
            while ((r = in.read(buf)) >= 0)
                resp.write(buf, 0, r);
            throw new IllegalStateException("HTTP " + code + "-" + new String(resp.toByteArray()));
        } catch (IOException e) {
            InputStream err = conn.getErrorStream();
            ByteArrayOutputStream resp = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int r;
            while ((r = err.read(buf)) >= 0)
                resp.write(buf, 0, r);
            throw new IllegalStateException(new String(resp.toByteArray()), e);
        }
    }
}
