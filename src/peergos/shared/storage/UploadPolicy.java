package peergos.shared.storage;

import peergos.server.storage.*;
import peergos.shared.io.ipfs.api.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.util.*;

import javax.crypto.*;
import javax.crypto.spec.*;
import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.time.*;
import java.time.temporal.*;
import java.util.*;
import java.util.stream.*;

public class UploadPolicy {

    static class Test {
        public static void main(String[] a) throws Exception {
            String accessKey = "";
            String secretKey = "";

            for (boolean useIllegalPayload: Arrays.asList(true, false)) {

                byte[] payload = new byte[1024];
                new Random(42).nextBytes(payload);
                Multihash contentHash = new RAMStorage().put(null, null, null, Arrays.asList(payload), null).join().get(0);
                String s3Key = DirectReadS3BlockStore.hashToKey(contentHash);
                String bucketName = "";
                String baseUrl = "https://" + bucketName + ".us-east-1.linodeobjects.com/";
                String region = "us-east-1";
                PresignedUrl url = preSignUrl(s3Key, payload.length, contentHash, true, baseUrl, region, bucketName, accessKey, secretKey);

                String res = new String(postMultipart(url.base, url.fields, useIllegalPayload ? new byte[1024] : payload));
//                String res = new String(put(new URI(url.base).toURL(), url.fields, useIllegalPayload ? new byte[1024] : payload));
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
                                    Multihash contentHash,
                                    boolean allowPublicReads,
                                    String baseUrl,
                                    String region,
                                    String bucketName,
                                    String accessKeyId,
                                    String s3SecretKey) {
        Instant instant = Instant.now();
//        String endpointUrl = String.format("https://s3.%s.amazonaws.com/%s", region, bucketName);

        Duration duration = Duration.of(1, ChronoUnit.HOURS);
        UploadPolicy policy = new UploadPolicy(key, size, contentHash, allowPublicReads, bucketName, accessKeyId, region, instant, duration);
        AwsPolicy awsPolicy = UploadPolicy.asAwsPolicy(policy);
        String signature = computeSignature(awsPolicy, policy, s3SecretKey);

        Map<String, String> fields = Stream.concat(
                awsPolicy.conditions.stream(),
                Stream.of(
                        new Pair<>("Policy", encodePolicy(awsPolicy)),
                        new Pair<>("X-Amz-Signature", signature)
                )
        ).collect(Collectors.toMap(p -> p.left, p -> p.right));

        return new PresignedUrl(baseUrl, fields);
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
    private static String computeSignature(AwsPolicy policyToSign,
                                           UploadPolicy policy,
                                           String s3SecretKey) {
        String encodedPolicy = encodePolicy(policyToSign);
        String shortDate = UploadPolicy.asAwsShortDate(policy.date);

        byte[] dateKey = hmacSha256("AWS4" + s3SecretKey, shortDate.getBytes());
        byte[] dateRegionKey = hmacSha256(dateKey, policy.region.getBytes());
        byte[] dateRegionServiceKey = hmacSha256(dateRegionKey, "s3".getBytes());
        byte[] signingKey = hmacSha256(dateRegionServiceKey, "aws4_request".getBytes());

        return ArrayOps.bytesToHex(hmacSha256(signingKey, encodedPolicy.getBytes()));
    }

    private static String encodePolicy(AwsPolicy policy) {
        String policyJson = JSONParser.toString(policy.toMap());
        return Base64.getEncoder().encodeToString(policyJson.getBytes(StandardCharsets.UTF_8));
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

    public static AwsPolicy asAwsPolicy(UploadPolicy policy) {
        String credentialsId = credentialsId(policy);
        List<Pair<String, String>> conditions = new ArrayList<>();

        conditions.add(new Pair<>("key", policy.key));
        conditions.add(new Pair<>("bucket", policy.bucket));
        conditions.add(new Pair<>("x-amz-algorithm", "AWS4-HMAC-SHA256"));
        conditions.add(new Pair<>("x-amz-credential", credentialsId));
        conditions.add(new Pair<>("x-amz-date", asAwsDate(policy.date)));
        conditions.add(new Pair<>("x-amz-content-sha256", ArrayOps.bytesToHex(policy.contentHash.getHash())));
        conditions.add(new Pair<>("content-type", "binary/octet-stream"));
        if (policy.allowPublicReads)
            conditions.add(new Pair<>("acl", "public-read"));

        Instant expirationTime = policy.date.plus(policy.untilExpiration);
        List<List<String>> extraConditions = new ArrayList<>();
        String size = Integer.toString(policy.size);
        extraConditions.add(Arrays.asList("content-length-range", size, size));
        return new AwsPolicy(expirationTime, conditions, extraConditions);
    }

    public final String key;
    public final int size;
    public final Multihash contentHash;
    public final boolean allowPublicReads;
    public final String bucket;
    public final String accessKeyId;
    public final String region;
    public final Instant date;
    public final Duration untilExpiration;

    public UploadPolicy(String key,
                        int size,
                        Multihash contentHash,
                        boolean allowPublicReads,
                        String bucket,
                        String accessKeyId,
                        String region,
                        Instant date,
                        Duration untilExpiration) {
        this.key = key;
        this.size = size;
        this.contentHash = contentHash;
        this.allowPublicReads = allowPublicReads;
        this.bucket = bucket;
        this.accessKeyId = accessKeyId;
        this.region = region;
        this.date = date;
        this.untilExpiration = untilExpiration;
    }

    private static String credentialsId(UploadPolicy policy) {
        return String.format(
                "%s/%s/%s/%s/%s",
                policy.accessKeyId,
                asAwsShortDate(policy.date),
                policy.region,
                "s3",
                "aws4_request"
        );
    }

    static class AwsPolicy {
        public final Instant expiration;
        public final List<Pair<String, String>> conditions;
        public final List<List<String>> extraConditions;

        public AwsPolicy(Instant expiration, List<Pair<String, String>> conditions, List<List<String>> extraConditions) {
            this.expiration = expiration;
            this.conditions = conditions;
            this.extraConditions = extraConditions;
        }

        public Object toMap() {
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("expiration", expiration.toString());
            List<Object> jsonConditions = new ArrayList<>();
            for (Pair<String, String> p : conditions) {
                Map<String, Object> condition = new LinkedHashMap<>();
                condition.put(p.left, p.right);
                jsonConditions.add(condition);
            }
            for (List<String> extraCondition : extraConditions) {
                jsonConditions.add(extraCondition);
            }
            res.put("conditions", jsonConditions);
            return res;
        }
    }
}

