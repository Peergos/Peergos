package peergos.server.storage;

import com.sun.net.httpserver.*;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public class LocalS3Server {
    private final HttpServer server;
    private final LocalS3Handler handler;

    public LocalS3Server(Path storageRoot, String bucket, String accessKey, String secretKey, int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 128);
        this.handler = new LocalS3Handler(storageRoot, bucket, accessKey, secretKey);
        server.createContext("/", handler);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    }

    /** Simulate s3 refusing to serve one key, as distinct from not having it. */
    public void refuseRequestsFor(String keyFragment) {
        handler.refuseRequestsFor(keyFragment);
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
    }

    /**
     * Returns an S3Config wired to this local server.
     * S3BlockStorage in non-HTTPS mode prepends the bucket to all paths, so
     * a block stored as "key" will live at storageRoot/bucket/key.
     */
    public static S3Config getConfig(String bucket, String accessKey, String secretKey, int port) {
        return new S3Config("", bucket, "us-east-1", accessKey, secretKey, "localhost:" + port, Optional.empty());
    }
}
