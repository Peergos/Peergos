package peergos.shared.storage.auth;

import org.w3c.dom.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.storage.*;
import peergos.shared.util.*;

import javax.crypto.*;
import javax.crypto.spec.*;
import javax.xml.parsers.*;
import javax.xml.xpath.*;
import java.io.*;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

/** Presign requests to Amazon S3 or compatible
 *
 * @link https://docs.aws.amazon.com/AmazonS3/latest/API/sig-v4-header-based-auth.html
 */
public class S3Request {

    private static final String ALGORITHM = "AWS4-HMAC-SHA256";
    public static final String UNSIGNED = "UNSIGNED-PAYLOAD";

    public final String verb, host;
    public final String key;
    public final String contentSha256;
    public final Optional<Integer> expiresSeconds;
    public final boolean allowPublicReads;
    public final boolean useAuthHeader;
    public final String accessKeyId;
    public final String region;
    public final Map<String, String> extraQueryParameters;
    public final Map<String, String> extraHeaders;
    public final String shortDate, datetime;

    public S3Request(String verb,
                     String host,
                     String key,
                     String contentSha256,
                     Optional<Integer> expiresSeconds,
                     boolean allowPublicReads,
                     boolean useAuthHeader,
                     Map<String, String> extraQueryParameters,
                     Map<String, String> extraHeaders,
                     String accessKeyId,
                     String region,
                     String datetime) {
        if (datetime.length() != 16)
            throw new IllegalStateException("Invalid datetime: " + datetime);
        this.verb = verb;
        this.host = host;
        this.key = key;
        this.contentSha256 = contentSha256;
        this.expiresSeconds = expiresSeconds;
        this.allowPublicReads = allowPublicReads;
        this.useAuthHeader = useAuthHeader;
        this.extraQueryParameters = extraQueryParameters;
        this.extraHeaders = extraHeaders;
        this.accessKeyId = accessKeyId;
        this.region = region;
        this.shortDate = datetime.substring(0, 8);
        this.datetime = datetime;
    }

    public static CompletableFuture<PresignedUrl> preSignPut(String key,
                                                             int size,
                                                             String contentSha256,
                                                             boolean allowPublicReads,
                                                             String datetime,
                                                             String host,
                                                             Map<String, String> extraHeaders,
                                                             String region,
                                                             String accessKeyId,
                                                             String s3SecretKey,
                                                             boolean useHttps,
                                                             Hasher h) {
        extraHeaders.put("Content-Length", "" + size);
        S3Request policy = new S3Request("PUT", host, key, contentSha256, Optional.empty(), allowPublicReads, true,
                Collections.emptyMap(), extraHeaders, accessKeyId, region, datetime);
        return preSignRequest(policy, key, host, s3SecretKey, useHttps, h);
    }

    public static CompletableFuture<PresignedUrl> preSignCopy(String sourceBucket,
                                                              String sourceKey,
                                                              String targetKey,
                                                              String datetime,
                                                              String host,
                                                              Map<String, String> extraHeaders,
                                                              String region,
                                                              String accessKeyId,
                                                              String s3SecretKey,
                                                              boolean useHttps,
                                                              Hasher h) {
        Map<String, String> extras = new TreeMap<>();
        extras.putAll(extraHeaders);
        extras.put("x-amz-copy-source", "/" + sourceBucket + "/" + sourceKey);
        S3Request policy = new S3Request("PUT", host, targetKey, UNSIGNED, Optional.empty(), false, true,
                Collections.emptyMap(), extras, accessKeyId, region, datetime);
        return preSignRequest(policy, targetKey, host, s3SecretKey, useHttps, h);
    }

    public static CompletableFuture<PresignedUrl> preSignGet(String key,
                                                             Optional<Integer> expirySeconds,
                                                             Optional<Pair<Integer, Integer>> range,
                                                             String datetime,
                                                             String host,
                                                             String region,
                                                             String accessKeyId,
                                                             String s3SecretKey,
                                                             boolean useHttps,
                                                             Hasher h) {
        return preSignNulliPotent("GET", key, expirySeconds, range, datetime, host, region, accessKeyId, s3SecretKey, useHttps, h);
    }

    public static CompletableFuture<PresignedUrl> preSignHead(String key,
                                                              Optional<Integer> expirySeconds,
                                                              String datetime,
                                                              String host,
                                                              String region,
                                                              String accessKeyId,
                                                              String s3SecretKey,
                                                              boolean useHttps,
                                                              Hasher h) {
        return preSignNulliPotent("HEAD", key, expirySeconds, Optional.empty(), datetime, host, region, accessKeyId, s3SecretKey, useHttps, h);
    }

    private static CompletableFuture<PresignedUrl> preSignNulliPotent(String verb,
                                                                      String key,
                                                                      Optional<Integer> expiresSeconds,
                                                                      Optional<Pair<Integer, Integer>> range,
                                                                      String datetime,
                                                                      String host,
                                                                      String region,
                                                                      String accessKeyId,
                                                                      String s3SecretKey,
                                                                      boolean useHttps,
                                                                      Hasher h) {

        Map<String, String> extraHeaders = range
                .map(p -> Stream.of(p).collect(Collectors.toMap(r -> "Range", r -> "bytes="+r.left+"-"+r.right)))
                .orElse(Collections.emptyMap());
        S3Request policy = new S3Request(verb, host, key, UNSIGNED, expiresSeconds, false, false,
                Collections.emptyMap(), extraHeaders, accessKeyId, region, datetime);
        return preSignRequest(policy, key, host, s3SecretKey, useHttps, h);
    }

    public static CompletableFuture<PresignedUrl> preSignRequest(S3Request req,
                                                                 String key,
                                                                 String host,
                                                                 String s3SecretKey,
                                                                 boolean useHttps,
                                                                 Hasher h) {
        return computeSignature(req, s3SecretKey, h).thenApply(signature -> {
            String query = req.getQueryString(signature);
            String protocol =  useHttps ? "https" : "http";
            return new PresignedUrl(protocol + "://" + host + "/" + key + query, req.getHeaders(signature));
        });
    }

    /**
     * Method for generating policy signature V4 for direct browser upload.
     *
     * @link https://docs.aws.amazon.com/AmazonS3/latest/API/sig-v4-authenticating-requests.html
     */
    public static CompletableFuture<String> computeSignature(S3Request policy,
                                                             String s3SecretKey,
                                                             Hasher hasher) {
        String stringToSign = policy.stringToSign();
        String shortDate = policy.shortDate;

        return hasher.hmacSha256(("AWS4" + s3SecretKey).getBytes(), shortDate.getBytes())
                .thenCompose(dateKey -> hasher.hmacSha256(dateKey, policy.region.getBytes()))
                .thenCompose(dateRegionKey -> hasher.hmacSha256(dateRegionKey, "s3".getBytes()))
                .thenCompose(dateRegionServiceKey -> hasher.hmacSha256(dateRegionServiceKey, "aws4_request".getBytes()))
                .thenCompose(signingKey -> hasher.hmacSha256(signingKey, stringToSign.getBytes()))
                .thenApply(ArrayOps::bytesToHex);
    }

    public String stringToSign() {
        StringBuilder res = new StringBuilder();
        res.append(ALGORITHM + "\n");
        res.append(datetime + "\n");
        res.append(scope() + "\n");
        res.append(ArrayOps.bytesToHex(Hash.sha256(toCanonicalRequest().getBytes())));
        return res.toString();
    }

    private static String urlEncode(String in) {
        try {
            return URLEncoder.encode(in, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    public String toCanonicalRequest() {
        StringBuilder res = new StringBuilder();
        res.append(verb + "\n");
        res.append("/" + key + "\n");

        res.append(getQueryParameters().entrySet()
                .stream()
                .map(e -> urlEncode(e.getKey()) + "=" + urlEncode(e.getValue()))
                .collect(Collectors.joining("&")));
        res.append("\n");

        Map<String, String> headers = getCanonicalHeaders();
        for (Map.Entry<String, String> e : headers.entrySet()) {
            res.append(e.getKey().toLowerCase() + ":" + e.getValue() + "\n");
        }
        res.append("\n");

        res.append(headersToSign() + "\n");
        res.append(contentSha256);
        return res.toString();
    }

    private Map<String, String> getHeaders(String signature) {
        Map<String, String> headers = getOriginalHeaders();
        if (! useAuthHeader)
            return headers;
        headers.put("Authorization", ALGORITHM + " Credential=" + credential()
                + ",SignedHeaders=" + headersToSign() + ",Signature=" + signature);
        return headers;
    }

    private Map<String, String> getOriginalHeaders() {
        Map<String, String> res = new LinkedHashMap<>();
        res.put("Host", host);
        if (! useAuthHeader)
            return res;
        res.put("x-amz-date", datetime);
        res.put("x-amz-content-sha256", contentSha256);
        for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
            res.put(e.getKey(), e.getValue());
        }
        if (allowPublicReads)
            res.put("x-amz-acl", "public-read");
        return res;
    }

    private String getQueryString(String signature) {
        Map<String, String> res = getQueryParameters();
        if (! useAuthHeader)
            res.put("X-Amz-Signature", signature);
        if (res.isEmpty())
            return "";
        return "?" + res.entrySet()
                .stream()
                .map(e -> urlEncode(e.getKey()) + "=" + urlEncode(e.getValue()))
                .collect(Collectors.joining("&"));
    }

    private Map<String, String> getQueryParameters() {
        Map<String, String> res = new TreeMap<>();
        res.putAll(extraQueryParameters);
        if (! useAuthHeader) {
            res.put("X-Amz-Algorithm", ALGORITHM);
            res.put("X-Amz-Credential", credential());
            res.put("X-Amz-Date", datetime);
            expiresSeconds.ifPresent(seconds -> res.put("X-Amz-Expires", "" + seconds));
            res.put("X-Amz-SignedHeaders", "host");
        }
        return res;
    }

    private SortedMap<String, String> getCanonicalHeaders() {
        SortedMap<String, String> res = new TreeMap<>();
        Map<String, String> originalHeaders = getOriginalHeaders();
        for (Map.Entry<String, String> e : originalHeaders.entrySet()) {
            res.put(e.getKey().toLowerCase(), e.getValue());
        }
        return res;
    }

    private String headersToSign() {
        return getCanonicalHeaders().keySet()
                .stream()
                .sorted()
                .collect(Collectors.joining(";"));
    }

    private String scope() {
        return shortDate + "/" + region +"/s3/aws4_request";
    }

    private String credential() {
        return accessKeyId +"/" + shortDate +"/" + region + "/s3/aws4_request";
    }

    public boolean isGet() {
        return "GET".equals(verb);
    }

    public boolean isHead() {
        return "HEAD".equals(verb);
    }

    public static String currentDatetime() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return now.toString().substring(0, 19).replaceAll("-", "").replaceAll(":", "") + "Z";
    }
}

