package peergos.server;

import io.prometheus.client.Counter;
import io.prometheus.client.exporter.HTTPServer;

import java.io.IOException;

/**
 * A wrapper around the prometheus metrics and HTTP exporter.
 */
public class AggregatedMetrics {
    private static Counter build(String name, String help) {
        return Counter.build()
                .name(name).help(help).register();
    }

    public static final Counter FOLLOW_REQUEST_COUNTER = build("send_follow_request_total","Total follow requests.");
    public static final Counter GET_FOLLOW_REQUEST_COUNTER = build("get_follow_request_counter", "Total get follow request calls.");
    public static final Counter REMOVE_FOLLOW_REQUEST_COUNTER = build("remove_follow_request_counter", "Total remove follow request calls.");

    public static final Counter PUBLIC_FILE_COUNTER = build("public_file_counter", "Total public files.");

    public static final Counter DHT_BLOCK_PUT  = build("dht_block_put", "Total DHT block puts.");
    public static final Counter DHT_BLOCK_GET  = build("dht_block_get", "Total DHT block gets.");

    public static final Counter MUTABLE_POINTERS_SET  = build("mutable_pointers_set", "Total mutable-pointers set calls.");
    public static final Counter MUTABLE_POINTERS_GET  = build("mutable_pointers_get", "Total mutable-pointers get calls.");

    public static final Counter GET_PUBLIC_KEY  = build("core_node_get_public_key", "Total get-public-key calls.");
    public static final Counter GET_PUBLIC_KEY_CHAIN  = build("core_node_get_chain", "Total get-public-key-chain calls.");
    public static final Counter UPDATE_PUBLIC_KEY_CHAIN  = build("core_node_update_chain", "Total getupdate-public-key-chain calls.");


    public static void startExporter(int port) throws IOException {
        HTTPServer server = new HTTPServer(port);
        //shutdown hook on signal
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop()));
    }
}

