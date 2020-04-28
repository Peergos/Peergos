package peergos.server.storage;

import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.storage.*;
import peergos.shared.util.*;

import java.io.*;
import java.net.*;
import java.time.*;
import java.util.*;

class S3Exploration {
    public static void main(String[] a) throws Exception {
        String accessKey = a[0];
        String secretKey = a[1];
        String bucketName = a[2];
        String region = "us-east-1";
        String regionEndpoint = region + ".linodeobjects.com";
        String host = bucketName + "." + regionEndpoint;

        for (boolean useIllegalPayload: Arrays.asList(false)) {
            // Test a list objects GET
            PresignedUrl listUrl = S3Request.preSignList("", 10, Optional.empty(),
                    ZonedDateTime.now(), host, region, accessKey, secretKey);
            String listRes = new String(get(new URI(listUrl.base).toURL(), listUrl.fields));

            // test a authed PUT
            byte[] payload = "Hi Linode2!".getBytes();
            Multihash content = new RAMStorage().put(null, null, null, Arrays.asList(payload), null).join().get(0);
            String s3Key = DirectS3BlockStore.hashToKey(content);
            Map<String, String> extraHeaders = new TreeMap<>();
            extraHeaders.put("Content-Type", "application/octet-stream");
            extraHeaders.put("User-Agent", "Bond, James Bond");

            boolean hashContent = true;
            String contentHash = hashContent ? ArrayOps.bytesToHex(content.getHash()) : "UNSIGNED-PAYLOAD";
            PresignedUrl putUrl = S3Request.preSignPut(s3Key, payload.length, contentHash, false,
                    ZonedDateTime.now().minusMinutes(14), host, extraHeaders, region, accessKey, secretKey);

            String res = new String(write(new URI(putUrl.base).toURL(), "PUT", putUrl.fields, useIllegalPayload ? new byte[payload.length] : payload));
            System.out.println(res);

            // Test a list objects GET continuation
            PresignedUrl list2Url = S3Request.preSignList("", 10, Optional.of(s3Key),
                    ZonedDateTime.now(), host, region, accessKey, secretKey);
            String list2Res = new String(get(new URI(list2Url.base).toURL(), list2Url.fields));

            // test an authed HEAD
            PresignedUrl headUrl = S3Request.preSignHead(s3Key, Optional.of(600), ZonedDateTime.now(), host, region, accessKey, secretKey);
            Map<String, List<String>> headRes = head(new URI(headUrl.base).toURL(), Collections.emptyMap());
            String size = headRes.get("Content-Length").get(0);
            System.out.println(size);

            // test an authed read
            PresignedUrl getUrl = S3Request.preSignGet(s3Key, Optional.of(600), ZonedDateTime.now(), host, region, accessKey, secretKey);
            String readRes = new String(get(new URI(getUrl.base).toURL(), getUrl.fields));
            System.out.println(readRes);

            // test an authed read which has expired
            PresignedUrl failGetUrl = S3Request.preSignGet(s3Key, Optional.of(600), ZonedDateTime.now().minusMinutes(11), host, region, accessKey, secretKey);
            String failReadRes = new String(get(new URI(failGetUrl.base).toURL(), failGetUrl.fields));
            System.out.println(failReadRes);

            // test a public read
            String webUrl = "https://" + bucketName + ".website-" + region + ".linodeobjects.com/" + s3Key;
            byte[] getResult = get(new URI(webUrl).toURL(), Collections.emptyMap());
            if (! Arrays.equals(getResult, payload))
                System.out.println("Incorrect contents!");

            // test a delete
            PresignedUrl delUrl = S3Request.preSignDelete(s3Key, ZonedDateTime.now(), host, region, accessKey, secretKey);
            delete(new URI(delUrl.base).toURL(), delUrl.fields);
            System.out.println();
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

    private static Map<String, List<String>> head(URL target, Map<String, String> headers) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) target.openConnection();
        conn.setRequestMethod("HEAD");
        for (Map.Entry<String, String> e : headers.entrySet()) {
            conn.setRequestProperty(e.getKey(), e.getValue());
        }

        try {
            int resp = conn.getResponseCode();
            if (resp == 200)
                return conn.getHeaderFields();
            throw new IllegalStateException("HTTP " + resp);
        } catch (IOException e) {
            InputStream err = conn.getErrorStream();
            ByteArrayOutputStream resp = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int r;
            while ((r = err.read(buf)) >= 0)
                resp.write(buf, 0, r);
            throw new IllegalStateException(new String(resp.toByteArray()));
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
            throw new IllegalStateException("HTTP " + code + "-" + resp.toByteArray());
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
