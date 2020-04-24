package peergos.shared.storage;

import peergos.server.storage.*;
import peergos.shared.io.ipfs.api.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.util.*;

import javax.crypto.*;
import javax.crypto.spec.*;
import java.io.*;
import java.net.*;
import java.security.*;
import java.time.*;
import java.time.temporal.*;
import java.util.*;
import java.util.stream.*;

public class UploadPolicy {

    static class Scratch {
        public static void main(String[] a) throws Exception {
            String accessKey = "";
            String secretKey = "";

            for (boolean useIllegalPayload: Arrays.asList(false)) {

                byte[] payload = new byte[1024];
                new Random(42).nextBytes(payload);
                Multihash contentHash = new RAMStorage().put(null, null, null, Arrays.asList(payload), null).join().get(0);
                String s3Key = DirectReadS3BlockStore.hashToKey(contentHash);
                String bucketName = "";
                String region = "us-east-1";
                String host = bucketName + "." + region + ".linodeobjects.com";
                String baseUrl = "https://" + host + "/";
                Map<String, String> extraHeaders = new TreeMap<>();
                extraHeaders.put("content-type", "binary/octet-stream");

                PresignedUrl url = preSignUrl(s3Key, payload.length, contentHash.getHash(), true,
                        Instant.now(), "PUT", host, extraHeaders, baseUrl, region, bucketName, accessKey, secretKey);

                String res = new String(put(new URI(url.base).toURL(), url.fields, useIllegalPayload ? new byte[1024] : payload));
                System.out.println(res);
                String webUrl = "https://" + bucketName + ".website-" + region + ".linodeobjects.com/" + s3Key;
                byte[] getResult = get(new URI(webUrl).toURL());
                if (!Arrays.equals(getResult, payload))
                    System.out.println("Incorrect contents!");
            }
        }

        private static byte[] put(URL target, Map<String, String> headers, byte[] body) throws Exception {
            HttpURLConnection conn = (HttpURLConnection) target.openConnection();
            conn.setRequestMethod("PUT");
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
                return resp.toByteArray();
            }
        }

        private static byte[] postMultipart(String url, Map<String, String> fields, byte[] file) {
            try {
                Multipart mPost = new Multipart(url, "UTF-8", fields);
                for (Map.Entry<String, String> e : fields.entrySet()) {
                    mPost.addFormField(e.getKey(), e.getValue());
                }
                mPost.addFilePart("file", new NamedStreamable.ByteArrayWrapper(file));
                return mPost.finish().getBytes();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        private static byte[] get(URL target) throws Exception {
            HttpURLConnection conn = (HttpURLConnection) target.openConnection();
            conn.setRequestMethod("GET");

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
    }

    public static class PresignedUrl {

        public final String base;
        public final Map<String, String> fields;

        public PresignedUrl(String base, Map<String, String> fields) {
            this.base = base;
            this.fields = fields;
        }
    }

    /**
     * Method mimics behavior of "createPresignedPost" operation from Node.js S3 SDK.
     *
     * @link https://docs.aws.amazon.com/AmazonS3/latest/API/sigv4-UsingHTTPPOST.html
     * @link https://docs.aws.amazon.com/AmazonS3/latest/API/sigv4-post-example.html
     * @link https://docs.aws.amazon.com/AmazonS3/latest/API/sigv4-HTTPPOSTForms.html
     * @link https://docs.aws.amazon.com/AWSJavaScriptSDK/latest/AWS/S3.html#createPresignedPost-property
     */
    public static PresignedUrl preSignUrl(String key,
                                          int size,
                                          byte[] contentSha256,
                                          boolean allowPublicReads,
                                          Instant now,
                                          String verb,
                                          String host,
                                          Map<String, String> extraHeaders,
                                          String baseUrl,
                                          String region,
                                          String bucketName,
                                          String accessKeyId,
                                          String s3SecretKey) {
        Duration duration = Duration.of(1, ChronoUnit.HOURS);
        UploadPolicy policy = new UploadPolicy(verb, host, key, size, contentSha256, allowPublicReads, extraHeaders,
                bucketName, accessKeyId, region, now, duration);

        String signature = computeSignature(policy, s3SecretKey);

        return new PresignedUrl(baseUrl, policy.getHeaders(signature));
    }

    private static byte[] hmacSha256(byte[] secretKeyBytes, byte[] message) {
        try {
            Mac mac = Mac.getInstance("HMACSHA256");
            SecretKey secretKey = new SecretKeySpec(secretKeyBytes, "HMACSHA256");
            mac.init(secretKey);
            return mac.doFinal(message);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] hmacSha256(String secretKey, byte[] message) {
        return hmacSha256(secretKey.getBytes(), message);
    }

    /**
     * Method for generating policy signature V4 for direct browser upload.
     *
     * @link https://docs.aws.amazon.com/AmazonS3/latest/API/sig-v4-authenticating-requests.html
     */
    private static String computeSignature(UploadPolicy policy,
                                           String s3SecretKey) {
        String stringToSign = policy.stringToSign();
        String shortDate = UploadPolicy.asAwsShortDate(policy.date);

        byte[] dateKey = hmacSha256("AWS4" + s3SecretKey, shortDate.getBytes());
        byte[] dateRegionKey = hmacSha256(dateKey, policy.region.getBytes());
        byte[] dateRegionServiceKey = hmacSha256(dateRegionKey, "s3".getBytes());
        byte[] signingKey = hmacSha256(dateRegionServiceKey, "aws4_request".getBytes());

        return ArrayOps.bytesToHex(hmacSha256(signingKey, stringToSign.getBytes()));
    }

    private static byte[] sha256(byte[] input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(input);
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("couldn't find hash algorithm");
        }
    }

    public SortedMap<String, String> getHeaders(String signature) {
        SortedMap<String, String> headers = getCanonicalHeaders();
        headers.put("Authorization", "AWS4-HMAC-SHA256 Credential=" + credential()
                + ",SignedHeaders=" + headersToSign() + ",Signature=" + signature);
        return headers;
    }

    public SortedMap<String, String> getCanonicalHeaders() {
        SortedMap<String, String> res = new TreeMap<>();
        res.put("host", host);
        res.put("x-amz-date", asAwsDate(date));
        res.put("x-amz-content-sha256", ArrayOps.bytesToHex(contentSha256));
        for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
            res.put(e.getKey(), e.getValue());
        }
        if (allowPublicReads)
            res.put("acl", "public-read");
        return res;
    }

    public String headersToSign() {
        return getCanonicalHeaders().keySet().stream().sorted().collect(Collectors.joining(";"));
    }

    public String toCanonicalRequest() {
        StringBuilder res = new StringBuilder();
        res.append(verb + "\n");
        res.append("/" + key + "\n");

        res.append("\n"); // no query parameters

        Map<String, String> headers = getCanonicalHeaders();
        for (Map.Entry<String, String> e : headers.entrySet()) {
            res.append(e.getKey() + ":" + e.getValue() + "\n");
        }
        res.append("\n");

        res.append(headersToSign() + "\n");
        res.append(ArrayOps.bytesToHex(contentSha256));
        return res.toString();
    }

    private String scope() {
        return String.format(
                "%s/%s/%s/%s",
                asAwsShortDate(date),
                region,
                "s3",
                "aws4_request");
    }

    public String stringToSign() {
        StringBuilder res = new StringBuilder();
        res.append("AWS4-HMAC-SHA256" + "\n");
        res.append(asAwsDate(date) + "\n");
        res.append(scope() + "\n");
        res.append(ArrayOps.bytesToHex(sha256(toCanonicalRequest().getBytes())));
        return res.toString();
    }

    /**
     * AWS date format converter.
     * Implementation taken directly from Node JS SDK
     */
    public static String asAwsDate(Instant instant) {
        return instant.toString()
                .replaceAll("[:\\-]|\\.\\d{3}", "");
    }

    /**
     * AWS short date format converter.
     * Implementation taken directly from Node JS SDK
     */
    public static String asAwsShortDate(Instant instant) {
        return asAwsDate(instant).substring(0, 8);
    }

    public final String verb, host;
    public final String key;
    public final int size;
    public final byte[] contentSha256;
    public final boolean allowPublicReads;
    public final String bucket;
    public final String accessKeyId;
    public final String region;
    public final Map<String, String> extraHeaders;
    public final Instant date;
    public final Duration untilExpiration;

    public UploadPolicy(String verb,
                        String host,
                        String key,
                        int size,
                        byte[] contentSha256,
                        boolean allowPublicReads,
                        Map<String, String> extraHeaders,
                        String bucket,
                        String accessKeyId,
                        String region,
                        Instant date,
                        Duration untilExpiration) {
        this.verb = verb;
        this.host = host;
        this.key = key;
        this.size = size;
        this.contentSha256 = contentSha256;
        this.allowPublicReads = allowPublicReads;
        this.extraHeaders = extraHeaders;
        this.bucket = bucket;
        this.accessKeyId = accessKeyId;
        this.region = region;
        this.date = date;
        this.untilExpiration = untilExpiration;
    }

    private String credential() {
        return String.format(
                "%s/%s/%s/%s/%s",
                accessKeyId,
                asAwsShortDate(date),
                region,
                "s3",
                "aws4_request"
        );
    }
}

