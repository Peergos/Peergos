package peergos.server.storage;

import peergos.shared.storage.*;
import peergos.shared.util.*;

import javax.crypto.*;
import javax.crypto.spec.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.stream.*;

public class S3Request {

    /**
     * Presign a url for a GET, PUT or POST
     *
     * @link https://docs.aws.amazon.com/AmazonS3/latest/API/sig-v4-header-based-auth.html
     */
    public static PresignedUrl preSignUrl(String key,
                                          int size,
                                          String contentSha256,
                                          boolean allowPublicReads,
                                          ZonedDateTime now,
                                          String verb,
                                          String host,
                                          Map<String, String> extraHeaders,
                                          String region,
                                          String accessKeyId,
                                          String s3SecretKey) {
        extraHeaders.put("Content-Length", "" + size);
        S3Request policy = new S3Request(verb, host, key, contentSha256, allowPublicReads, extraHeaders,
                accessKeyId, region, now.withNano(0).withZoneSameInstant(ZoneId.of("UTC")).toInstant());

        String signature = computeSignature(policy, s3SecretKey);

        return new PresignedUrl("https://" + host + "/" + key, policy.getHeaders(signature));
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
    public static String computeSignature(S3Request policy,
                                          String s3SecretKey) {
        String stringToSign = policy.stringToSign();
        String shortDate = S3Request.asAwsShortDate(policy.date);

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

    public Map<String, String> getHeaders(String signature) {
        Map<String, String> headers = getOriginalHeaders();
        headers.put("Authorization", "AWS4-HMAC-SHA256 Credential=" + credential()
                + ",SignedHeaders=" + headersToSign() + ",Signature=" + signature);
        return headers;
    }

    public Map<String, String> getOriginalHeaders() {
        Map<String, String> res = new LinkedHashMap<>();
        res.put("Host", host);
        res.put("x-amz-date", asAwsDate(date));
        res.put("x-amz-content-sha256", contentSha256);
        for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
            res.put(e.getKey(), e.getValue());
        }
        if (allowPublicReads)
            res.put("x-amz-acl", "public-read");
        return res;
    }

    public SortedMap<String, String> getCanonicalHeaders() {
        SortedMap<String, String> res = new TreeMap<>();
        Map<String, String> originalHeaders = getOriginalHeaders();
        for (Map.Entry<String, String> e : originalHeaders.entrySet()) {
            res.put(e.getKey().toLowerCase(), e.getValue());
        }
        return res;
    }

    public String headersToSign() {
        return getCanonicalHeaders().keySet()
                .stream()
                .sorted()
                .collect(Collectors.joining(";"));
    }

    public String toCanonicalRequest() {
        StringBuilder res = new StringBuilder();
        res.append(verb + "\n");
        res.append("/" + key + "\n");

        res.append("\n"); // no query parameters

        Map<String, String> headers = getCanonicalHeaders();
        for (Map.Entry<String, String> e : headers.entrySet()) {
            res.append(e.getKey().toLowerCase() + ":" + e.getValue() + "\n");
        }
        res.append("\n");

        res.append(headersToSign() + "\n");
        res.append(contentSha256);
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

    public static String asAwsDate(Instant instant) {
        return instant.toString()
                .replaceAll("[:\\-]|\\.\\d{3}", "");
    }

    public static String asAwsShortDate(Instant instant) {
        return asAwsDate(instant).substring(0, 8);
    }

    public final String verb, host;
    public final String key;
    public final String contentSha256;
    public final boolean allowPublicReads;
    public final String accessKeyId;
    public final String region;
    public final Map<String, String> extraHeaders;
    public final Instant date;

    public S3Request(String verb,
                     String host,
                     String key,
                     String contentSha256,
                     boolean allowPublicReads,
                     Map<String, String> extraHeaders,
                     String accessKeyId,
                     String region,
                     Instant date) {
        this.verb = verb;
        this.host = host;
        this.key = key;
        this.contentSha256 = contentSha256;
        this.allowPublicReads = allowPublicReads;
        this.extraHeaders = extraHeaders;
        this.accessKeyId = accessKeyId;
        this.region = region;
        this.date = date;
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

