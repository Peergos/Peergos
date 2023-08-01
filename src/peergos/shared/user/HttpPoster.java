package peergos.shared.user;

import java.util.*;
import java.util.concurrent.*;

public interface HttpPoster {

    CompletableFuture<byte[]> post(String url, byte[] payload, boolean unzip, int timeoutMillis);

    default CompletableFuture<byte[]> post(String url, byte[] payload, boolean unzip) {
        return post(url, payload, unzip, 15_000);
    }

    CompletableFuture<byte[]> postUnzip(String url, byte[] payload, int timeoutMillis);

    default CompletableFuture<byte[]> postUnzip(String url, byte[] payload) {
        return postUnzip(url, payload, 15_000);
    }

    default CompletableFuture<byte[]> postMultipart(String url, List<byte[]> files) {
        return postMultipart(url, files, -1);
    }

    CompletableFuture<byte[]> postMultipart(String url, List<byte[]> files, int timeoutMillis);

    CompletableFuture<byte[]> put(String url, byte[] payload, Map<String, String> headers);

    CompletableFuture<byte[]> get(String url, Map<String, String> headers);

    default CompletableFuture<byte[]> get(String url) {
        // This changes to a POST with an empty body
        // The reason for this is browsers allow any website to do a get request to localhost
        // but they block POST requests. So this prevents random websites from calling APIs on localhost
        return postUnzip(url, new byte[0]);
    }

}
