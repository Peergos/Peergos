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
    public static final Counter DHT_CHAMP_GET  = build("dht_champ_get", "Total champ gets");
    public static final Histogram DHT_CHAMP_GET_DURATION = Histogram.build()
            .labelNames("duration")
            .name("champ_get_duration")
            .help("Time to respond to a champ.get call")
            .exponentialBuckets(0.01, 2, 16)
            .register();

    public static final Counter MUTABLE_POINTERS_SET  = build("mutable_pointers_set", "Total mutable-pointers set calls.");
    public static final Counter MUTABLE_POINTERS_GET  = build("mutable_pointers_get", "Total mutable-pointers get calls.");

    public static final Counter LOGIN_SET  = build("login_set", "Total login set calls.");
    public static final Counter LOGIN_GET  = build("login_get", "Total login get calls.");
    public static final Counter LOGIN_GET_MFA  = build("login_get_mfa", "Total get mfa calls.");
    public static final Counter LOGIN_ADD_TOTP  = build("login_add_totp", "Total add totp calls.");
    public static final Counter LOGIN_ENABLE_TOTP  = build("login_enable_totp", "Total enable totp calls.");

    public static final Counter BAT_ADD  = build("bat_add", "Total addBat calls.");
    public static final Counter BATS_GET  = build("bats_get", "Total getBats calls.");

    public static final Counter GET_ALL_USERNAMES  = build("core_node_get_all_usernames", "Total get-all-usernames calls.");
    public static final Counter GET_USERNAME  = build("core_node_get_username", "Total get-username calls.");
    public static final Counter GET_PUBLIC_KEY  = build("core_node_get_public_key", "Total get-public-key calls.");
    public static final Counter GET_PUBLIC_KEY_CHAIN  = build("core_node_get_chain", "Total get-public-key-chain calls.");
    public static final Counter UPDATE_PUBLIC_KEY_CHAIN  = build("core_node_update_chain", "Total getupdate-public-key-chain calls.");
    public static final Counter PKI_RATE_LIMITED  = build("pki_rate_limited", "Total number of pki updates rate limited.");
    public static final Counter SIGNUP  = build("core_node_signup", "Total signup calls.");
    public static final Counter PAID_SIGNUP_START  = build("core_node_signup_paid_start", "Total start paid signup calls.");
    public static final Counter PAID_SIGNUP_COMPLETE  = build("core_node_signup_paid_complete", "Total complete paid signup calls.");
    public static final Counter PAID_SIGNUP_SUCCESS  = build("core_node_signup_paid_success", "Total successful paid signup calls.");
    public static final Counter MIGRATE_USER  = build("core_node_migrate_user", "Total migrate-user calls.");

    public static void startExporter(String address, int port) throws IOException {
        Logging.LOG().info("Starting metrics server at " + address + ":" + port);
        HTTPServer server = new HTTPServer(address, port);
        //shutdown hook on signal
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.close()));
    }
}

