package peergos.shared.user;

import java.util.*;
import java.util.concurrent.*;

public interface HttpPoster {

    CompletableFuture<byte[]> post(String url, byte[] payload, boolean unzip);

    CompletableFuture<byte[]> postUnzip(String url, byte[] payload);

    CompletableFuture<byte[]> postMultipart(String url, List<byte[]> files);

    CompletableFuture<byte[]> get(String url);

}
