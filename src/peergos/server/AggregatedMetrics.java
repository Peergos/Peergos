package peergos.server;

import io.prometheus.client.Counter;
import io.prometheus.client.Histogram;
import io.prometheus.client.exporter.HTTPServer;
import peergos.server.util.*;

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

    public static final Counter DHT_ID  = build("dht_id", "Total id calls.");
    public static final Counter DHT_BLOCK_PUT  = build("dht_block_put", "Total DHT block puts.");
    public static final Counter DHT_BLOCK_GET  = build("dht_block_get", "Total DHT block gets.");
    public static final Counter DHT_BLOCK_STAT  = build("dht_block_stat", "Total DHT block stats.");
    public static final Counter DHT_BLOCK_REFS  = build("dht_block_refs", "Total DHT block refs.");
    public static final Counter DHT_TRANSACTION_START  = build("dht_transaction_start", "Total DHT transaction starts.");
    public static final Counter DHT_TRANSACTION_CLOSE  = build("dht_transaction_close", "Total DHT transaction closes.");

    public static final Counter MUTABLE_POINTERS_SET  = build("mutable_pointers_set", "Total mutable-pointers set calls.");
    public static final Counter MUTABLE_POINTERS_GET  = build("mutable_pointers_get", "Total mutable-pointers get calls.");

    public static final Counter GET_ALL_USERNAMES  = build("core_node_get_all_usernames", "Total get-all-usernames calls.");
    public static final Counter GET_USERNAME  = build("core_node_get_username", "Total get-username calls.");
    public static final Counter GET_PUBLIC_KEY  = build("core_node_get_public_key", "Total get-public-key calls.");
    public static final Counter GET_PUBLIC_KEY_CHAIN  = build("core_node_get_chain", "Total get-public-key-chain calls.");
    public static final Counter UPDATE_PUBLIC_KEY_CHAIN  = build("core_node_update_chain", "Total getupdate-public-key-chain calls.");
    public static final Counter MIGRATE_USER  = build("core_node_migrate_user", "Total migrate-user calls.");

    public static final Histogram IPFS_PRE_GC_DURATION = Histogram.build()
            .name("ipfs_pre_gc")
            .exponentialBuckets(1, 2, 20)
            .help("Time (ms) to wait to start IPFS GC")
            .register();
    public static final Histogram IPFS_GC_DURATION  = Histogram.build()
            .name("ipfs_gc_duration")
            .exponentialBuckets(1, 2, 20)
            .help("IPFS GC Duration (ms).")
            .register();



    public static void startExporter(String address, int port) throws IOException {
        Logging.LOG().info("Starting metrics server at " + address + ":" + port);
        HTTPServer server = new HTTPServer(address, port);
        //shutdown hook on signal
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop()));
    }
}

