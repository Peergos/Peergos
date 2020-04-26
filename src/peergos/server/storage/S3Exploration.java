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

        for (boolean useIllegalPayload: Arrays.asList(false)) {

            byte[] payload = "Hi Linode!".getBytes();
            Multihash content = new RAMStorage().put(null, null, null, Arrays.asList(payload), null).join().get(0);
            String s3Key = DirectS3BlockStore.hashToKey(content);
            String host = bucketName + "." + region + ".linodeobjects.com";
            Map<String, String> extraHeaders = new TreeMap<>();
//            extraHeaders.put("Origin", "https://test.peergos.net");
            extraHeaders.put("Content-Type", "application/octet-stream");
            extraHeaders.put("User-Agent", "Bond, James Bond");

            boolean hashContent = true;
            String contentHash = hashContent ? ArrayOps.bytesToHex(content.getHash()) : "UNSIGNED-PAYLOAD";
            String method = "PUT";
            PresignedUrl url = S3Request.preSignUrl(s3Key, payload.length, contentHash, false,
                    ZonedDateTime.now().minusMinutes(14), method, host, extraHeaders, region, accessKey, secretKey);

            String res = new String(write(new URI(url.base).toURL(), method, url.fields, useIllegalPayload ? new byte[payload.length] : payload));
            System.out.println(res);
            String webUrl = "https://" + bucketName + ".website-" + region + ".linodeobjects.com/" + s3Key;
            byte[] getResult = get(new URI(webUrl).toURL());
            if (! Arrays.equals(getResult, payload))
                System.out.println("Incorrect contents!");
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
