package peergos.server.net;

import peergos.server.util.JavaPoster;
import peergos.shared.io.ipfs.api.NamedStreamable;
import peergos.shared.storage.StorageQuotaExceededException;
import peergos.shared.util.*;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.channels.Channels;
import java.nio.channels.Pipe;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;

public class Multipart {
    private static final String LINE_FEED = "\r\n";
    public static final ExecutorService ioPool = Executors.newCachedThreadPool();
    private final String boundary;
    private final CompletableFuture<byte[]> res;
    private String charset;
    private OutputStream out;
    private PrintWriter writer;

    public Multipart(HttpClient client, String requestURL, String charset, Map<String, String> headers, int readTimeoutMillis) throws IOException {
        this.charset = charset;
        this.boundary = createBoundary();

        URI uri = URI.create(new URL(requestURL).toString());
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder().uri(uri);
        requestBuilder.setHeader("User-Agent", "Java Peergos Client");
        requestBuilder.setHeader("Content-Type", "multipart/form-data; boundary=" + boundary);
        if (readTimeoutMillis > 0)
            requestBuilder.timeout(Duration.ofMillis(readTimeoutMillis));
        for (Map.Entry<String, String> e : headers.entrySet()) {
            requestBuilder.setHeader(e.getKey(), e.getValue());
        }

        Pipe pipe = Pipe.open();

        requestBuilder.POST(HttpRequest.BodyPublishers.ofInputStream(() -> Channels.newInputStream(pipe.source())));

        HttpRequest request = requestBuilder.build();

        out = Channels.newOutputStream(pipe.sink());
        writer = new PrintWriter(new OutputStreamWriter(out, charset), true);
        res = new CompletableFuture<>();
        CompletableFuture.runAsync(() -> {
            HttpResponse<InputStream> response = null;
            try {
                response = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream()).join();
                HttpHeaders responseHeaders = response.headers();
                Optional<String> contentEncodingOpt = responseHeaders.firstValue("content-encoding");
                boolean isGzipped = contentEncodingOpt.isPresent() && "gzip".equals(contentEncodingOpt.get());
                DataInputStream din = new DataInputStream(isGzipped ?
                        new GZIPInputStream(response.body()) :
                        response.body());
                byte[] resp = Serialize.readFully(din);
                din.close();
                int statusCode = response.statusCode();
                if (statusCode == 200) {
                    res.complete(resp);
                } else if (statusCode == HttpURLConnection.HTTP_NO_CONTENT) {
                    res.complete(new byte[0]);
                } else {
                    List<String> trailers = responseHeaders.map().get("trailer");
                    String trailer = String.join("", trailers);
                    if (trailer.contains("Storage+quota+reached"))
                        res.completeExceptionally(new StorageQuotaExceededException(trailer));
                    else
                        res.completeExceptionally(new IOException(trailer));
                }
            } catch (HttpTimeoutException e) {
                res.completeExceptionally(new SocketTimeoutException("Socket timeout on: " + request.uri()));
            } catch (IOException e) {
                JavaPoster.handleError(request.uri().toString(), res, response, e);
            } catch (Exception e) {
                res.completeExceptionally(e);
            } finally {
                writer.close();
            }
        }, ioPool);
    }

    public static String createBoundary() {
        Random r = new Random();
        String allowed = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
        StringBuilder b = new StringBuilder();
        for (int i=0; i < 32; i++)
            b.append(allowed.charAt(r.nextInt(allowed.length())));
        return b.toString();
    }

    public void addFormField(String name, String value) {
        writer.append("--" + boundary).append(LINE_FEED);
        writer.append("Content-Disposition: form-data; name=\"" + name + "\"")
                .append(LINE_FEED);
        writer.append("Content-Type: text/plain; charset=" + charset).append(
                LINE_FEED);
        writer.append(LINE_FEED);
        writer.append(value).append(LINE_FEED);
        writer.flush();
    }

    /** Recursive call to add a subtree to this post
     *
     * @param path
     * @param dir
     * @throws IOException
     */
    public void addSubtree(String path, File dir) throws IOException {
        String dirPath = path + (path.length() > 0 ? "/" : "") + dir.getName();
        addDirectoryPart(dirPath);
        for (File f: dir.listFiles()) {
            if (f.isDirectory())
                addSubtree(dirPath, f);
            else
                addFilePart("file", new NamedStreamable.NativeFile(dirPath + "/", f));
        }
    }

    public void addDirectoryPart(String path) {
        try {
            writer.append("--" + boundary).append(LINE_FEED);
            writer.append("Content-Disposition: file; filename=\"" + URLEncoder.encode(path, "UTF-8") + "\"").append(LINE_FEED);
            writer.append("Content-Type: application/x-directory").append(LINE_FEED);
            writer.append("Content-Transfer-Encoding: binary").append(LINE_FEED);
            writer.append(LINE_FEED);
            writer.append(LINE_FEED);
            writer.flush();
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    public void addFilePart(String fieldName, NamedStreamable uploadFile) throws IOException {
        Optional<String> fileName = uploadFile.getName();
        writer.append("--" + boundary).append(LINE_FEED);
        if (!fileName.isPresent())
            writer.append("Content-Disposition: file; name=\"" + fieldName + "\";").append(LINE_FEED);
        else
            writer.append("Content-Disposition: file; filename=\"" + fileName.get() + "\"").append(LINE_FEED);
        writer.append("Content-Type: application/octet-stream").append(LINE_FEED);
        writer.append("Content-Transfer-Encoding: binary").append(LINE_FEED);
        writer.append(LINE_FEED);
        writer.flush();

        InputStream inputStream = uploadFile.getInputStream();
        byte[] buffer = new byte[4096];
        int r;
        while ((r = inputStream.read(buffer)) != -1)
            out.write(buffer, 0, r);
        out.flush();
        inputStream.close();

        writer.append(LINE_FEED);
        writer.flush();
    }

    public void addHeaderField(String name, String value) {
        writer.append(name + ": " + value).append(LINE_FEED);
        writer.flush();
    }

    public CompletableFuture<byte[]> finish() throws IOException {
        writer.append("--" + boundary + "--").append(LINE_FEED);
        writer.close();

        return res;
    }
}
