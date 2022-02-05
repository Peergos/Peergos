package peergos.server.storage;

import org.w3c.dom.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.storage.*;
import peergos.shared.storage.auth.*;

import javax.xml.parsers.*;
import javax.xml.xpath.*;
import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

public class S3AdminRequests {

    public static S3Request buildReq(String verb,
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
                                     ZonedDateTime timestamp) {
        return new S3Request(verb, host, key, contentSha256, expiresSeconds, allowPublicReads, useAuthHeader, extraQueryParameters,
                extraHeaders, accessKeyId, region, asAwsDate(normaliseDate(timestamp)));
    }
    public static String asAwsTime(ZonedDateTime timestamp) {
        return asAwsDate(normaliseDate(timestamp));
    }

    private static Instant normaliseDate(ZonedDateTime timestamp) {
        return timestamp.withNano(0).withZoneSameInstant(ZoneId.of("UTC")).toInstant();
    }

    public static String asAwsDate(Instant instant) {
        return instant.toString()
                .replaceAll("[:\\-]|\\.\\d{3}", "");
    }

    public static String asAwsDate(ZonedDateTime time) {
        return asAwsDate(normaliseDate(time));
    }

    private static String asAwsShortDate(Instant instant) {
        return asAwsDate(instant).substring(0, 8);
    }

    private static XPathFactory xPathFactory = XPathFactory.newInstance();
    public static final ThreadLocal<DocumentBuilder> builder =
            new ThreadLocal<>() {
                @Override
                protected DocumentBuilder initialValue() {
                    try {
                        return DocumentBuilderFactory.newInstance().newDocumentBuilder();
                    } catch (ParserConfigurationException exc) {
                        throw new IllegalArgumentException(exc);
                    }
                }
            };

    public static class ObjectMetadata {
        public final String key, etag;
        public final LocalDateTime lastModified;
        public final long size;

        public ObjectMetadata(String key, String etag, LocalDateTime lastModified, long size) {
            this.key = key;
            this.etag = etag;
            this.lastModified = lastModified;
            this.size = size;
        }
    }

    public static class ListObjectsReply {
        public final String prefix;
        public final boolean isTruncated;
        public final List<ObjectMetadata> objects;
        public final Optional<String> continuationToken;

        public ListObjectsReply(String prefix, boolean isTruncated, List<ObjectMetadata> objects, Optional<String> continuationToken) {
            this.prefix = prefix;
            this.isTruncated = isTruncated;
            this.objects = objects;
            this.continuationToken = continuationToken;
        }
    }

    public static CompletableFuture<PresignedUrl> preSignList(String prefix,
                                                              int maxKeys,
                                                              Optional<String> continuationToken,
                                                              ZonedDateTime now,
                                                              String host,
                                                              String region,
                                                              String accessKeyId,
                                                              String s3SecretKey,
                                                              boolean useHttps,
                                                              Hasher h) {
        Map<String, String> extraQueryParameters = new LinkedHashMap<>();
        extraQueryParameters.put("list-type", "2");
        extraQueryParameters.put("max-keys", "" + maxKeys);
        extraQueryParameters.put("fetch-owner", "false");
        extraQueryParameters.put("prefix", prefix);
        continuationToken.ifPresent(t -> extraQueryParameters.put("continuation-token", t));

        Instant normalised = normaliseDate(now);
        S3Request policy = new S3Request("GET", host, "", S3Request.UNSIGNED, Optional.empty(), false, true,
                extraQueryParameters, Collections.emptyMap(), accessKeyId, region, asAwsDate(normalised));
        return S3Request.preSignRequest(policy, "", host, s3SecretKey, useHttps, h);
    }

    public static ListObjectsReply listObjects(String prefix,
                                               int maxKeys,
                                               Optional<String> continuationToken,
                                               ZonedDateTime now,
                                               String host,
                                               String region,
                                               String accessKeyId,
                                               String s3SecretKey,
                                               Function<PresignedUrl, byte[]> getter,
                                               Supplier<DocumentBuilder> builder,
                                               boolean useHttps,
                                               Hasher h) {
        PresignedUrl listReq = preSignList(prefix, maxKeys, continuationToken, now, host, region, accessKeyId, s3SecretKey, useHttps, h).join();
        try {
            Document xml = builder.get().parse(new ByteArrayInputStream(getter.apply(listReq)));
            List<ObjectMetadata> res = new ArrayList<>();
            Node root = xml.getFirstChild();
            NodeList topLevel = root.getChildNodes();
            boolean isTruncated = false;
            Optional<String> nextContinuationToken = Optional.empty();
            for (int t=0; t < topLevel.getLength(); t++) {
                Node top = topLevel.item(t);
                if ("IsTruncated".equals(top.getNodeName())) {
                    String val = top.getTextContent();
                    isTruncated = "true".equals(val);
                }
                if ("NextContinuationToken".equals(top.getNodeName())) {
                    String val = top.getTextContent();
                    nextContinuationToken = Optional.of(val);
                }
                if ("Contents".equals(top.getNodeName())) {
                    NodeList childNodes = top.getChildNodes();
                    String key=null, etag=null, modified=null;
                    long size=0;
                    for (int i = 0; i < childNodes.getLength(); i++) {
                        Node n = childNodes.item(i);
                        if ("Key".equals(n.getNodeName())) {
                            key = n.getTextContent();
                        } else if ("LastModified".equals(n.getNodeName())) {
                            modified = n.getTextContent();
                        } else if ("ETag".equals(n.getNodeName())) {
                            etag = n.getTextContent();
                        } else if ("Size".equals(n.getNodeName())) {
                            size = Long.parseLong(n.getTextContent());
                        }
                    }
                    res.add(new ObjectMetadata(key, etag, LocalDateTime.parse(modified.substring(0, modified.length() - 1)), size));
                }
            }
            return new ListObjectsReply(prefix, isTruncated, res, nextContinuationToken);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static class BulkDeleteReply {
        public final List<String> deletedKeys;

        public BulkDeleteReply(List<String> deletedKeys) {
            this.deletedKeys = deletedKeys;
        }
    }

    public static BulkDeleteReply bulkDelete(List<String> keys,
                                             ZonedDateTime now,
                                             String host,
                                             String region,
                                             String accessKeyId,
                                             String s3SecretKey,
                                             Function<byte[], String> sha256,
                                             BiFunction<PresignedUrl, byte[], byte[]> poster,
                                             Supplier<DocumentBuilder> builder,
                                             boolean useHttps,
                                             Hasher h) {
        StringBuilder xmlBuilder = new StringBuilder();
        xmlBuilder.append("<Delete>");
        for (String key : keys) {
            xmlBuilder.append("<Object><Key>");
            xmlBuilder.append(key);
            xmlBuilder.append("</Key></Object>");
        }
        xmlBuilder.append("</Delete>");
        String reqXml = xmlBuilder.toString();
        byte[] body = reqXml.getBytes();
        String contentSha256 = sha256.apply(body);
        Map<String, String> extraHeaders = new TreeMap<>();
        extraHeaders.put("Content-Length", "" + body.length);
        Map<String, String> extraQueryParameters = new TreeMap<>();
        extraQueryParameters.put("delete", "true");
        Instant normalised = normaliseDate(now);
        S3Request policy = new S3Request("POST", host, "", contentSha256, Optional.empty(), false, true,
                extraQueryParameters, extraHeaders, accessKeyId, region, asAwsDate(normalised));
        PresignedUrl reqUrl = S3Request.preSignRequest(policy, "", host, s3SecretKey, useHttps, h).join();
        byte[] respBytes = poster.apply(reqUrl, body);
        try {
            Document xml = builder.get().parse(new ByteArrayInputStream(respBytes));
            List<String> deleted = new ArrayList<>();
            Node root = xml.getFirstChild();
            NodeList topLevel = root.getChildNodes();
            for (int t=0; t < topLevel.getLength(); t++) {
                Node top = topLevel.item(t);
                if ("Deleted".equals(top.getNodeName())) {
                    NodeList childNodes = top.getChildNodes();
                    for (int i = 0; i < childNodes.getLength(); i++) {
                        Node n = childNodes.item(i);
                        if ("Key".equals(n.getNodeName())) {
                            deleted.add(n.getTextContent());
                        }
                    }
                }
            }
            return new BulkDeleteReply(deleted);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
