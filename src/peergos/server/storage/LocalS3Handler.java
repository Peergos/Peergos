package peergos.server.storage;

import com.sun.net.httpserver.*;
import org.w3c.dom.*;

import javax.crypto.*;
import javax.crypto.spec.*;
import javax.xml.parsers.*;
import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.time.format.*;
import java.time.temporal.*;
import java.util.*;
import java.util.stream.*;

class LocalS3Handler implements HttpHandler {
    private static final String ALGORITHM = "AWS4-HMAC-SHA256";
    private static final String UNSIGNED = "UNSIGNED-PAYLOAD";

    private final Path storageRoot;
    private final String bucket;
    private final String accessKey;
    private final String secretKey;
    private static final DateTimeFormatter S3_DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'");

    LocalS3Handler(Path storageRoot, String bucket, String accessKey, String secretKey) {
        this.storageRoot = storageRoot;
        this.bucket = bucket;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            verifySignature(exchange);
            String method = exchange.getRequestMethod().toUpperCase();
            String rawPath = exchange.getRequestURI().getRawPath();
            Map<String, String> qp = parseQueryParams(exchange.getRequestURI().getRawQuery());

            switch (method) {
                case "GET":
                    if (qp.containsKey("versions"))
                        handleListVersions(exchange, qp);
                    else if (qp.containsKey("list-type"))
                        handleListObjects(exchange, qp);
                    else
                        handleGet(exchange, rawPath);
                    break;
                case "HEAD":
                    handleHead(exchange, rawPath);
                    break;
                case "PUT":
                    String copySource = firstHeader(exchange, "x-amz-copy-source");
                    if (copySource != null)
                        handleCopy(exchange, rawPath, copySource);
                    else
                        handlePut(exchange, rawPath);
                    break;
                case "DELETE":
                    handleDelete(exchange, rawPath);
                    break;
                case "POST":
                    if (qp.containsKey("delete"))
                        handleBulkDelete(exchange);
                    else
                        sendXmlError(exchange, 400, "InvalidRequest", "Unknown POST");
                    break;
                default:
                    sendXmlError(exchange, 405, "MethodNotAllowed", method);
            }
        } catch (SignatureException e) {
            sendXmlError(exchange, 403, "SignatureDoesNotMatch", e.getMessage());
        } catch (FileNotFoundException e) {
            sendXmlError(exchange, 404, "NoSuchKey", "The specified key does not exist.");
        } catch (Exception e) {
            sendXmlError(exchange, 500, "InternalError", e.getMessage() != null ? e.getMessage() : e.getClass().getName());
        }
    }

    // ── Storage helpers ──────────────────────────────────────────────────────

    private Path keyToPath(String rawPath) throws IOException {
        String key = rawPath.startsWith("/") ? rawPath.substring(1) : rawPath;
        key = URLDecoder.decode(key, "UTF-8");
        Path target = storageRoot.resolve(key).normalize();
        if (!target.startsWith(storageRoot))
            throw new IOException("Path traversal: " + rawPath);
        return target;
    }

    private void handleGet(HttpExchange exchange, String rawPath) throws IOException {
        Path file = keyToPath(rawPath);
        if (!Files.exists(file)) throw new FileNotFoundException(rawPath);
        byte[] data = Files.readAllBytes(file);
        exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
        exchange.getResponseHeaders().set("ETag", "\"" + etag(data) + "\"");
        exchange.sendResponseHeaders(200, data.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(data); }
    }

    private void handleHead(HttpExchange exchange, String rawPath) throws IOException {
        Path file = keyToPath(rawPath);
        if (!Files.exists(file)) throw new FileNotFoundException(rawPath);
        exchange.getResponseHeaders().set("Content-Length", String.valueOf(Files.size(file)));
        exchange.sendResponseHeaders(200, -1);
    }

    private void handlePut(HttpExchange exchange, String rawPath) throws IOException {
        Path file = keyToPath(rawPath);
        Files.createDirectories(file.getParent());
        byte[] body = exchange.getRequestBody().readAllBytes();
        Files.write(file, body);
        exchange.getResponseHeaders().set("ETag", "\"" + etag(body) + "\"");
        exchange.sendResponseHeaders(200, 0);
        exchange.getResponseBody().close();
    }

    private void handleCopy(HttpExchange exchange, String rawPath, String copySource) throws IOException {
        // copySource is /bucket/key or bucket/key
        String srcPath = copySource.startsWith("/") ? copySource : "/" + copySource;
        Path src = keyToPath(srcPath);
        if (!Files.exists(src)) throw new FileNotFoundException(copySource);
        Path dst = keyToPath(rawPath);
        Files.createDirectories(dst.getParent());
        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
        String modified = Instant.now().truncatedTo(ChronoUnit.SECONDS)
                .atOffset(ZoneOffset.UTC).format(S3_DATE);
        sendXml(exchange, 200,
                "<CopyObjectResult><LastModified>" + modified + "Z</LastModified><ETag>\"copied\"</ETag></CopyObjectResult>");
    }

    private void handleDelete(HttpExchange exchange, String rawPath) throws IOException {
        Path file = keyToPath(rawPath);
        Files.deleteIfExists(file);
        exchange.sendResponseHeaders(204, -1);
    }

    private void handleBulkDelete(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        List<String> keys = parseDeleteXml(body);
        StringBuilder sb = new StringBuilder("<DeleteResult>");
        for (String key : keys) {
            Files.deleteIfExists(keyToPath("/" + key));
            sb.append("<Deleted><Key>").append(escapeXml(key)).append("</Key></Deleted>");
        }
        sb.append("</DeleteResult>");
        sendXml(exchange, 200, sb.toString());
    }

    private void handleListVersions(HttpExchange exchange, Map<String, String> qp) throws IOException {
        String prefix = qp.getOrDefault("prefix", "");
        int maxKeys = Integer.parseInt(qp.getOrDefault("max-keys", "1000"));
        String keyMarker = qp.getOrDefault("key-marker", "");

        List<String> keys = listKeysWithPrefix(prefix).stream()
                .filter(k -> keyMarker.isEmpty() || k.compareTo(keyMarker) > 0)
                .collect(Collectors.toList());

        boolean truncated = keys.size() > maxKeys;
        List<String> page = truncated ? keys.subList(0, maxKeys) : keys;

        StringBuilder sb = new StringBuilder("<ListVersionsResult>");
        sb.append("<IsTruncated>").append(truncated).append("</IsTruncated>");
        if (truncated) {
            String last = page.get(page.size() - 1);
            sb.append("<NextKeyMarker>").append(escapeXml(last)).append("</NextKeyMarker>");
            sb.append("<NextVersionIdMarker>null</NextVersionIdMarker>");
        }
        for (String key : page) {
            Path file = storageRoot.resolve(key);
            long size = Files.size(file);
            String modified = Files.getLastModifiedTime(file).toInstant()
                    .truncatedTo(ChronoUnit.SECONDS).atOffset(ZoneOffset.UTC)
                    .format(S3_DATE);
            sb.append("<Version>");
            sb.append("<Key>").append(escapeXml(key)).append("</Key>");
            sb.append("<VersionId>null</VersionId>");
            sb.append("<IsLatest>true</IsLatest>");
            sb.append("<LastModified>").append(modified).append("</LastModified>");
            sb.append("<ETag>\"etag\"</ETag>");
            sb.append("<Size>").append(size).append("</Size>");
            sb.append("</Version>");
        }
        sb.append("</ListVersionsResult>");
        sendXml(exchange, 200, sb.toString());
    }

    private void handleListObjects(HttpExchange exchange, Map<String, String> qp) throws IOException {
        String prefix = qp.getOrDefault("prefix", "");
        int maxKeys = Integer.parseInt(qp.getOrDefault("max-keys", "1000"));
        String contToken = qp.getOrDefault("continuation-token", "");

        List<String> keys = listKeysWithPrefix(prefix).stream()
                .filter(k -> contToken.isEmpty() || k.compareTo(contToken) > 0)
                .collect(Collectors.toList());

        boolean truncated = keys.size() > maxKeys;
        List<String> page = truncated ? keys.subList(0, maxKeys) : keys;

        StringBuilder sb = new StringBuilder("<ListBucketResult>");
        sb.append("<IsTruncated>").append(truncated).append("</IsTruncated>");
        if (truncated)
            sb.append("<NextContinuationToken>").append(escapeXml(page.get(page.size() - 1))).append("</NextContinuationToken>");
        for (String key : page) {
            Path file = storageRoot.resolve(key);
            long size = Files.size(file);
            String modified = Files.getLastModifiedTime(file).toInstant()
                    .truncatedTo(ChronoUnit.SECONDS).atOffset(ZoneOffset.UTC)
                    .format(S3_DATE);
            sb.append("<Contents>");
            sb.append("<Key>").append(escapeXml(key)).append("</Key>");
            sb.append("<LastModified>").append(modified).append("</LastModified>");
            sb.append("<ETag>\"etag\"</ETag>");
            sb.append("<Size>").append(size).append("</Size>");
            sb.append("</Contents>");
        }
        sb.append("</ListBucketResult>");
        sendXml(exchange, 200, sb.toString());
    }

    private List<String> listKeysWithPrefix(String prefix) throws IOException {
        Path prefixPath = storageRoot.resolve(prefix).normalize();
        Path walkFrom = Files.isDirectory(prefixPath) ? prefixPath : prefixPath.getParent();
        if (walkFrom == null || !walkFrom.startsWith(storageRoot) || !Files.exists(walkFrom))
            return Collections.emptyList();
        try (Stream<Path> stream = Files.walk(walkFrom)) {
            return stream
                    .filter(Files::isRegularFile)
                    .map(p -> storageRoot.relativize(p).toString().replace(File.separatorChar, '/'))
                    .filter(k -> k.startsWith(prefix))
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    private static List<String> parseDeleteXml(byte[] body) {
        try {
            DocumentBuilder db = DocumentBuilderFactory.newInstance().newDocumentBuilder();
            Document doc = db.parse(new ByteArrayInputStream(body));
            NodeList objects = doc.getElementsByTagName("Object");
            List<String> keys = new ArrayList<>();
            for (int i = 0; i < objects.getLength(); i++) {
                NodeList children = objects.item(i).getChildNodes();
                for (int j = 0; j < children.getLength(); j++) {
                    Node child = children.item(j);
                    if ("Key".equals(child.getNodeName()))
                        keys.add(child.getTextContent());
                }
            }
            return keys;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse delete XML", e);
        }
    }

    // ── Signature verification ───────────────────────────────────────────────

    private void verifySignature(HttpExchange exchange) throws SignatureException {
        try {
            URI uri = exchange.getRequestURI();
            String rawQuery = uri.getRawQuery();
            Map<String, String> qp = parseQueryParams(rawQuery);
            Headers headers = exchange.getRequestHeaders();

            boolean queryParamAuth = qp.containsKey("X-Amz-Signature");
            String authHeader = firstHeader(exchange, "authorization");
            boolean headerAuth = authHeader != null && authHeader.startsWith(ALGORITHM);

            if (!queryParamAuth && !headerAuth)
                throw new SignatureException("No AWS authentication found");

            String providedSig, datetime, signedHeaders, credentialScope, payloadHash;

            if (queryParamAuth) {
                providedSig = urlDecode(qp.get("X-Amz-Signature"));
                datetime = urlDecode(qp.getOrDefault("X-Amz-Date", ""));
                signedHeaders = urlDecode(qp.getOrDefault("X-Amz-SignedHeaders", "host"));
                String cred = urlDecode(qp.getOrDefault("X-Amz-Credential", ""));
                int slash = cred.indexOf('/');
                credentialScope = slash >= 0 ? cred.substring(slash + 1) : cred;
                payloadHash = UNSIGNED;
            } else {
                Map<String, String> authParts = parseAuthorization(authHeader);
                providedSig = authParts.getOrDefault("Signature", "");
                String cred = authParts.getOrDefault("Credential", "");
                int slash = cred.indexOf('/');
                credentialScope = slash >= 0 ? cred.substring(slash + 1) : cred;
                signedHeaders = authParts.getOrDefault("SignedHeaders", "");
                datetime = firstHeader(exchange, "x-amz-date");
                if (datetime == null) datetime = "";
                payloadHash = firstHeader(exchange, "x-amz-content-sha256");
                if (payloadHash == null) payloadHash = UNSIGNED;
            }

            if (datetime.length() < 8)
                throw new SignatureException("Missing or invalid x-amz-date");
            String shortDate = datetime.substring(0, 8);

            // Extract region from credentialScope: DATE/REGION/s3/aws4_request
            String[] scopeParts = credentialScope.split("/");
            String region = scopeParts.length > 1 ? scopeParts[1] : "us-east-1";

            // Canonical request
            String method = exchange.getRequestMethod().toUpperCase();
            String canonicalUri = uri.getRawPath();
            if (canonicalUri == null || canonicalUri.isEmpty()) canonicalUri = "/";
            String canonicalQS = buildCanonicalQueryString(rawQuery, queryParamAuth);
            String canonicalHeaders = buildCanonicalHeaders(exchange, signedHeaders);

            String canonicalRequest = method + "\n" + canonicalUri + "\n" + canonicalQS + "\n" +
                    canonicalHeaders + "\n" + signedHeaders + "\n" + payloadHash;

            String stringToSign = ALGORITHM + "\n" + datetime + "\n" + credentialScope + "\n" +
                    hex(sha256(canonicalRequest.getBytes(StandardCharsets.UTF_8)));

            byte[] signingKey = deriveSigningKey(secretKey, shortDate, region);
            String expectedSig = hex(hmac(signingKey, stringToSign.getBytes(StandardCharsets.UTF_8)));

            if (!MessageDigest.isEqual(expectedSig.getBytes(StandardCharsets.UTF_8),
                    providedSig.getBytes(StandardCharsets.UTF_8)))
                throw new SignatureException("Signature mismatch");

        } catch (SignatureException e) {
            throw e;
        } catch (Exception e) {
            throw new SignatureException("Signature verification error: " + e.getMessage());
        }
    }

    private static String buildCanonicalQueryString(String rawQuery, boolean queryParamAuth) {
        if (rawQuery == null || rawQuery.isEmpty()) return "";
        List<String[]> params = new ArrayList<>();
        for (String part : rawQuery.split("&")) {
            int eq = part.indexOf('=');
            String k = eq < 0 ? part : part.substring(0, eq);
            String v = eq < 0 ? "" : part.substring(eq + 1);
            String dk = urlDecode(k);
            if (queryParamAuth && "X-Amz-Signature".equals(dk)) continue;
            params.add(new String[]{dk, urlDecode(v)});
        }
        return params.stream()
                .sorted(Comparator.comparing(p -> urlEncode(p[0])))
                .map(p -> urlEncode(p[0]) + "=" + urlEncode(p[1]))
                .collect(Collectors.joining("&"));
    }

    private static String buildCanonicalHeaders(HttpExchange exchange, String signedHeaders) {
        StringBuilder sb = new StringBuilder();
        for (String name : signedHeaders.split(";")) {
            String value;
            if ("host".equalsIgnoreCase(name)) {
                value = exchange.getRequestHeaders().getFirst("Host");
                if (value == null) {
                    URI uri = exchange.getRequestURI();
                    value = uri.getHost();
                    if (uri.getPort() != -1) value += ":" + uri.getPort();
                }
            } else {
                value = firstHeader(exchange, name);
            }
            sb.append(name.toLowerCase()).append(":").append(value != null ? value.trim() : "").append("\n");
        }
        return sb.toString();
    }

    private static byte[] deriveSigningKey(String secret, String shortDate, String region) {
        byte[] kDate   = hmac(("AWS4" + secret).getBytes(StandardCharsets.UTF_8), shortDate.getBytes(StandardCharsets.UTF_8));
        byte[] kRegion = hmac(kDate, region.getBytes(StandardCharsets.UTF_8));
        byte[] kSvc    = hmac(kRegion, "s3".getBytes(StandardCharsets.UTF_8));
        return hmac(kSvc, "aws4_request".getBytes(StandardCharsets.UTF_8));
    }

    // ── Crypto utils ─────────────────────────────────────────────────────────

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) { throw new RuntimeException(e); }
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) { throw new RuntimeException(e); }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte v : b) sb.append(String.format("%02x", v & 0xff));
        return sb.toString();
    }

    private static String etag(byte[] data) {
        return hex(sha256(data));
    }

    // ── HTTP / string utils ──────────────────────────────────────────────────

    private static Map<String, String> parseQueryParams(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) return Collections.emptyMap();
        Map<String, String> result = new LinkedHashMap<>();
        for (String part : rawQuery.split("&")) {
            int eq = part.indexOf('=');
            if (eq < 0) result.put(urlDecode(part), "");
            else result.put(urlDecode(part.substring(0, eq)), urlDecode(part.substring(eq + 1)));
        }
        return result;
    }

    private static Map<String, String> parseAuthorization(String auth) {
        // "AWS4-HMAC-SHA256 Credential=X,SignedHeaders=Y,Signature=Z"
        Map<String, String> result = new HashMap<>();
        int space = auth.indexOf(' ');
        if (space < 0) return result;
        for (String part : auth.substring(space + 1).split(",")) {
            int eq = part.indexOf('=');
            if (eq > 0) result.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
        }
        return result;
    }

    private static String firstHeader(HttpExchange exchange, String name) {
        for (Map.Entry<String, List<String>> e : exchange.getRequestHeaders().entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) {
                List<String> vals = e.getValue();
                return vals.isEmpty() ? null : vals.get(0);
            }
        }
        return null;
    }

    private static String urlDecode(String s) {
        try { return URLDecoder.decode(s, "UTF-8"); }
        catch (Exception e) { return s; }
    }

    private static String urlEncode(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); }
        catch (Exception e) { return s; }
    }

    private static String escapeXml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static void sendXml(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/xml");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
    }

    private static void sendXmlError(HttpExchange exchange, int code, String error, String message) throws IOException {
        sendXml(exchange, code, "<Error><Code>" + error + "</Code><Message>" + escapeXml(message != null ? message : "") + "</Message></Error>");
    }

    private static class SignatureException extends Exception {
        SignatureException(String msg) { super(msg); }
    }
}
