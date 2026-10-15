package peergos.server;

import peergos.server.crypto.hash.ScryptJava;
import peergos.server.corenode.*;
import peergos.server.net.GcHandler;
import peergos.server.space.*;
import peergos.server.sql.*;
import peergos.server.storage.*;
import peergos.server.storage.auth.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.storage.*;
import peergos.shared.util.*;

import java.io.*;
import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.function.*;

public class GcCLI extends Builder {

    public static final Command<Boolean> GC = new Command<>("gc",
            "Garbage collect the blockstore, in the running server if there is one, otherwise in this process",
            GcCLI::gc,
            // read the same config as the daemon, but don't require anything that it requires
            Main.PEERGOS.params.stream()
                    .map(p -> p.defaultValue
                            .map(d -> new Command.Arg(p.name, p.description, false, d))
                            .orElseGet(() -> new Command.Arg(p.name, p.description, false)))
                    .toList()
    );

    public static Path tokenFile(Args a) {
        return a.fromPeergosDir("gc-token-file", "gc.token");
    }

    private static boolean gc(Args a) {
        int port = a.getInt("port");
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        try {
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/" + Constants.GC))
                    .POST(HttpRequest.BodyPublishers.noBody());
            Path tokenFile = tokenFile(a);
            if (Files.exists(tokenFile))
                req.header(GcHandler.TOKEN_HEADER, Files.readString(tokenFile).trim());
            System.out.println("Running gc in the server on port " + port + "...");
            HttpResponse<String> res = client.send(req.build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 404)
                return fail("The server on port " + port + " has no gc api. Stop it to run gc.");
            if (res.statusCode() == 403)
                return fail("The server refused the gc token. Run this as a user that can read " + tokenFile + " with the same PEERGOS_PATH as the server.");
            if (res.statusCode() != 200)
                return fail(res.body());
            System.out.println(res.body());
            return true;
        } catch (ConnectException e) {
            return gcInProcess(a, port);
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean gcInProcess(Args a, int port) {
        String listenHost = a.getArg(Main.LISTEN_HOST.name);
        // A server bound to a non loopback interface isn't reachable on localhost, so check the port
        // is free before touching its dbs. Holding it also stops a server starting until we're done.
        try (ServerSocket guard = new ServerSocket()) {
            try {
                guard.bind(new InetSocketAddress(listenHost, port));
            } catch (BindException e) {
                return fail("Port " + port + " on " + listenHost + " is in use, but its gc api isn't reachable on localhost. Stop the server to run gc.");
            }
            System.out.println("No server running, running gc in this process...");
            gcStorage(a, port);
            System.out.println("GC complete");
            return true;
        } catch (IOException | SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static void gcStorage(Args a, int port) throws SQLException {
        SqliteJdbcNativeLoader.load(a.getOptionalArg("native-sqlite-path").map(Paths::get));
        Crypto crypto = initCrypto(new ScryptJava(), a.getOptionalArg("native-tweetnacl-path").map(Paths::get));
        PublicSigningKey.addProvider(PublicSigningKey.Type.Ed25519, crypto.signer);
        SqlSupplier sqlCommands = getSqlCommands(a);

        Supplier<Connection> dbConnectionPool = getDBConnector(a, "transactions-sql-file");
        JdbcServerIdentityStore ids = JdbcServerIdentityStore.build(getDBConnector(a, "serverids-file", dbConnectionPool), sqlCommands, crypto);
        if (ids.getIdentities().isEmpty())
            throw new IllegalStateException("This server has never been started, there is nothing to collect");
        PartitionStatus partitionStatus = new JdbcPartitionStatus(getDBConnector(a, "partition-status-file"), sqlCommands);
        if (! partitionStatus.isDone())
            throw new IllegalStateException("The blockstore hasn't been partitioned yet, start the server to complete it before running gc");

        JdbcBatCave batStore = new JdbcBatCave(getDBConnector(a, "bat-store", dbConnectionPool), sqlCommands);
        BlockRequestAuthoriser blockAuth = blockAuthoriser(a, batStore, crypto.hasher);
        BlockMetadataStore meta = buildBlockMetadata(a);
        TransactionStore transactions = buildTransactionStore(a, dbConnectionPool);
        UsageStore usage = new JdbcUsageStore(getDBConnector(a, "space-usage-sql-file", dbConnectionPool), sqlCommands);
        JdbcIpnsAndSocial rawPointers = buildRawPointers(a, getDBConnector(a, "mutable-pointers-file", dbConnectionPool));
        // build the same storage as a server with gc enabled
        DeletableContentAddressedStorage storage = buildLocalStorage(a.with("enable-gc", "true"), linkHost(a, port),
                meta, batStore, transactions, blockAuth, ids, usage, rawPointers, partitionStatus, crypto.hasher);
        CoreNode pki = new HTTPCoreNode(buildP2pHttpProxy(a), getPkiServerId(a));

        boolean listRawBlocks = S3Config.useS3(a) && a.getBoolean(Main.VERSIONED_S3.name);
        GarbageCollector gc = new GarbageCollector(storage, rawPointers, usage, pki, a.fromPeergosDir("", ""),
                (cd, rd, c, u) -> Futures.of(true), username -> Futures.of(true), listRawBlocks);
        gc.collect(s -> Futures.of(true));
    }

    private static boolean fail(String message) {
        System.err.println(message);
        System.exit(1);
        return false;
    }
}
