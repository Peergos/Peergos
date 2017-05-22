package peergos.shared.user;

import peergos.shared.io.ipfs.api.*;
import peergos.shared.util.*;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

public class JavaPoster implements HttpPoster {

    private final URL dht;

    public JavaPoster(URL dht) {
        this.dht = dht;
    }

    public URL buildURL(String method) throws IOException {
        try {
            return new URL(dht, method);
        } catch (MalformedURLException mexican) {
            throw new IOException(mexican);
        }
    }

    @Override
    public CompletableFuture<byte[]> postUnzip(String url, byte[] payload) {
        return post(url, payload, true);
    }

    @Override
    public CompletableFuture<byte[]> post(String url, byte[] payload, boolean unzip) {
        HttpURLConnection conn = null;
        CompletableFuture<byte[]> res = new CompletableFuture<>();
        try
        {
            conn = (HttpURLConnection) buildURL(url).openConnection();
            conn.setDoInput(true);
            conn.setDoOutput(true);
            DataOutputStream dout = new DataOutputStream(conn.getOutputStream());

            dout.write(payload);
            dout.flush();

            String contentEncoding = conn.getContentEncoding();
            boolean isGzipped = "gzip".equals(contentEncoding);
            DataInputStream din = new DataInputStream(isGzipped && unzip ? new GZIPInputStream(conn.getInputStream()) : conn.getInputStream());
            byte[] resp = Serialize.readFully(din);
            din.close();
            res.complete(resp);
        } catch (IOException e) {
            res.completeExceptionally(e);
        } finally {
            if (conn != null)
                conn.disconnect();
        }
        return res;
    }

    @Override
    public CompletableFuture<byte[]> postMultipart(String url, List<byte[]> files) {
        try {
            Multipart mPost = new Multipart(buildURL(url).toString(), "UTF-8");
            for (byte[] file : files)
                mPost.addFilePart("file", new NamedStreamable.ByteArrayWrapper(file));
            return CompletableFuture.completedFuture(mPost.finish().getBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public CompletableFuture<byte[]> get(String url) {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) buildURL(url).openConnection();
            conn.setDoInput(true);

            String contentEncoding = conn.getContentEncoding();
            boolean isGzipped = "gzip".equals(contentEncoding);
            DataInputStream din = new DataInputStream(isGzipped ? new GZIPInputStream(conn.getInputStream()) : conn.getInputStream());
            return CompletableFuture.completedFuture(Serialize.readFully(din));
        } catch (IOException e) {
            CompletableFuture<byte[]> res = new CompletableFuture<>();
            res.completeExceptionally(e);
            return res;
        } finally {
            if (conn != null)
                conn.disconnect();
        }
    }

    @Override
    public String toString() {
        return dht.toString();
    }
}
