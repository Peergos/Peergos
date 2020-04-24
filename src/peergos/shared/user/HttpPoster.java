package peergos.shared.user;

import java.util.*;
import java.util.concurrent.*;

public interface HttpPoster {

    CompletableFuture<byte[]> post(String url, byte[] payload, boolean unzip);

    CompletableFuture<byte[]> postUnzip(String url, byte[] payload);

    CompletableFuture<byte[]> postMultipart(String url, List<byte[]> files);

    default CompletableFuture<byte[]> get(String url) {
        // This changes to a POST with an empty body
        // The reason for this is browsers allow any website to do a get request to localhost
        // but they block POST requests. So this prevents random websites from calling APIs on localhost
        return postUnzip(url, new byte[0]);
    }

}
