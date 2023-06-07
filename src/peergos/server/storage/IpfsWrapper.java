package peergos.server.storage;

import com.sun.net.httpserver.HttpServer;
import io.libp2p.core.PeerId;
import io.libp2p.core.crypto.PrivKey;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import org.peergos.*;
import org.peergos.client.RequestSender;
import org.peergos.config.*;
import org.peergos.config.Filter;
import org.peergos.net.APIHandler;
import org.peergos.net.HttpProxyHandler;
import org.peergos.protocol.http.HttpProtocol;
import peergos.server.util.*;
import peergos.server.util.Args;
import peergos.shared.io.ipfs.api.*;
import peergos.shared.io.ipfs.multiaddr.MultiAddress;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.storage.*;
import peergos.shared.util.*;

import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.logging.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.peergos.EmbeddedIpfs.buildBlockStore;
import static peergos.server.util.Logging.LOG;
import static peergos.server.util.AddressUtil.getAddress;

public class IpfsWrapper implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(IpfsWrapper.class.getName());

    public static final String IPFS_BOOTSTRAP_NODES = "ipfs-config-bootstrap-node-list";

    private static HttpProtocol.HttpRequestProcessor proxyHandler(io.ipfs.multiaddr.MultiAddress target) {
        return (s, req, h) -> {
            try {
                FullHttpResponse reply = RequestSender.proxy(target, (FullHttpRequest) req);
                h.accept(reply.retain());
            } catch (IOException ioe) {
                FullHttpResponse exceptionReply = org.peergos.util.HttpUtil.replyError(ioe);
                h.accept(exceptionReply.retain());
            }
        };
    }

    public static class ConfigParams {
        /**
         * Encapsulate IPFS configuration state.
         */
        public final Optional<List<MultiAddress>> bootstrapNode;
        public final int swarmPort;
        public final String apiAddress, gatewayAddress, allowTarget;
        public final List<IpfsInstaller.Plugin> plugins;
        public final Optional<String> metricsAddress;

        public ConfigParams(Optional<List<MultiAddress>> bootstrapNode,
                      String apiAddress,
                      String gatewayAddress,
                      String allowTarget,
                      int swarmPort,
                      Optional<String> metricsAddress,
                      List<IpfsInstaller.Plugin> plugins) {
            this.bootstrapNode = bootstrapNode;
            this.apiAddress = apiAddress;
            this.gatewayAddress = gatewayAddress;
            this.allowTarget = allowTarget;
            this.swarmPort = swarmPort;
            this.metricsAddress = metricsAddress;
            this.plugins = plugins;
        }
    }

    private static List<MultiAddress> parseMultiAddresses(String s) {
        return Stream.of(s.split(","))
                .filter(e -> ! e.isEmpty())
                .map(MultiAddress::new)
                .collect(Collectors.toList());
    }

    public static ConfigParams buildConfig(Args args) {
        Optional<List<MultiAddress>> bootstrapNodes = args.hasArg(IPFS_BOOTSTRAP_NODES)
                && args.getArg(IPFS_BOOTSTRAP_NODES).trim().length() > 0 ?
                Optional.of(parseMultiAddresses(args.getArg(IPFS_BOOTSTRAP_NODES))) :
                Optional.empty();

        String apiAddress = args.getArg("ipfs-api-address");
        String gatewayAddress = args.getArg("ipfs-gateway-address");
        MultiAddress allowTarget = new MultiAddress(args.getArg("allow-target"));
        int swarmPort = args.getInt("ipfs-swarm-port", 4001);

        boolean enableMetrics = args.getBoolean("collect-metrics", false);
        Optional<String> metricsAddress = enableMetrics ?
                Optional.of(args.getArg("metrics.address") + ":" + args.getInt("ipfs.metrics.port")) :
                Optional.empty();
        List<IpfsInstaller.Plugin> plugins = IpfsInstaller.Plugin.parseAll(args);


        return new ConfigParams(bootstrapNodes, apiAddress, gatewayAddress,
                "http://" + allowTarget.getHost() + ":" + allowTarget.getTCPPort(),
                swarmPort, metricsAddress, plugins);
    }

    private static final String IPFS_DIR = "IPFS_PATH";
    private static final String DEFAULT_DIR_NAME = ".ipfs";

    public final Path ipfsDir;
    public final ConfigParams configParams;
    public final MultiAddress proxyTarget;

    private static EmbeddedIpfs embeddedIpfs;
    private static HttpServer apiServer;

    public IpfsWrapper(Path ipfsDir, ConfigParams configParams, MultiAddress proxytarget) {

        File ipfsDirF = ipfsDir.toFile();
        if (! ipfsDirF.isDirectory() && ! ipfsDirF.mkdirs()) {
            throw new IllegalStateException("Specified IPFS_PATH '" + ipfsDir + " is not a directory and/or could not be created");
        }

        this.ipfsDir = ipfsDir;
        this.configParams = configParams;
        this.proxyTarget = proxytarget;
    }

    /**
     * Wait until the ipfs id comamnd returns a sensible response.
     * <p>
     * The ipfs daemon can take up to 30 seconds to start
     * responding to requests once the daemon is started.
     */
    public void waitForDaemon(int timeoutSeconds) {
        long start = System.currentTimeMillis();
        double duration = 0;

        while (duration < timeoutSeconds) {
            if (isHttpApiListening(configParams.apiAddress))
                return;

            try {
                Thread.sleep(1000);
            } catch (InterruptedException ie) {
            }

            long current = System.currentTimeMillis();

            duration = (double) (current - start) / 1000.0;
        }

        // still not ready?
        throw new IllegalStateException("ipfs daemon is not ready after specified timeout " + timeoutSeconds + " seconds.");
    }

    public static boolean isHttpApiListening(String ipfsApiAddress) {
        try {
            MultiAddress ipfsApi = new MultiAddress(ipfsApiAddress);
            ContentAddressedStorage.HTTP api = new ContentAddressedStorage.HTTP(new JavaPoster(getAddress(ipfsApi), false), false, null);
            api.id().get();
            return true;
        } catch (Exception e) {
            if (!(e.getCause() instanceof ConnectException))
                e.printStackTrace();
        }
        return false;
    }

    @Override
    public synchronized void close() {
        stop();
    }

    public synchronized void stop() {
        LOG.info("Stopping server...");
        try {
            embeddedIpfs.stop().join();
            apiServer.stop(3); //wait max 3 seconds
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    public static Path getIpfsDir(Args args) {
        //$IPFS_DIR, defaults to $PEERGOS_PATH/.ipfs
        return args.hasArg(IPFS_DIR) ?
                PathUtil.get(args.getArg(IPFS_DIR)) :
                args.fromPeergosDir(IPFS_DIR, DEFAULT_DIR_NAME);
    }

    public static IpfsWrapper build(Args args) {
        Path ipfsDir = getIpfsDir(args);

        LOG().info("Using IPFS dir " + ipfsDir);
        ConfigParams configParams = buildConfig(args);
        return new IpfsWrapper(ipfsDir, configParams, new MultiAddress(args.getArg("proxy-target")));
    }

    /**
     * Build an IpfsWrapper based on args.
     * <p>
     * Start running it in a sub-process.
     *
     * Block until the ipfs-daemon is ready for requests.
     *
     * Restart the daemon if it dies.
     *
     * @param args
     * @return
     */
    public static IpfsWrapper launch(Args args) {
        IpfsWrapper ipfs = IpfsWrapper.build(args);
        return launch(ipfs);
    }


    public static IpfsWrapper launch(IpfsWrapper ipfsWrapper) {
        Config config = ipfsWrapper.configure();
        LOG.info("Starting Nabu version: " + APIHandler.CURRENT_VERSION);
        BlockRequestAuthoriser authoriser = (c, b, p, a) -> CompletableFuture.completedFuture(true);

        embeddedIpfs = EmbeddedIpfs.build(ipfsWrapper.ipfsDir,
                buildBlockStore(config, ipfsWrapper.ipfsDir),
                List.of(config.addresses.getSwarmAddresses().stream().findFirst().get()),
                config.bootstrap.getBootstrapAddresses(),
                config.identity,
                authoriser,
                config.addresses.proxyTargetAddress.map(IpfsWrapper::proxyHandler)
        );
        embeddedIpfs.start();
        String apiAddressArg = "Addresses.API";
        io.ipfs.multiaddr.MultiAddress apiAddress = config.addresses.apiAddress;
        InetSocketAddress localAPIAddress = new InetSocketAddress(apiAddress.getHost(), apiAddress.getPort());

        int maxConnectionQueue = 500;
        int handlerThreads = 50;
        LOG.info("Starting RPC API server at " + apiAddress.getHost() + ":" + localAPIAddress.getPort());
        try {
            apiServer = HttpServer.create(localAPIAddress, maxConnectionQueue);
        } catch (IOException ioe) {
            throw new IllegalStateException("Unable to start APIServer");
        }
        apiServer.createContext(APIHandler.API_URL, new APIHandler(embeddedIpfs));
        if (config.addresses.proxyTargetAddress.isPresent())
            apiServer.createContext(HttpProxyService.API_URL, new HttpProxyHandler(new HttpProxyService(embeddedIpfs.node, embeddedIpfs.p2pHttp.get(), embeddedIpfs.dht)));
        apiServer.setExecutor(Executors.newFixedThreadPool(handlerThreads));
        apiServer.start();

        Thread shutdownHook = new Thread(() -> {
            ipfsWrapper.stop();
        });
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        return ipfsWrapper;
    }

    public Config configure() {
        Config config = null;
        Path configFilePath = ipfsDir.resolve("config");
        if (! configFilePath.toFile().exists()) {
            LOG().info("Initializing ipfs");
    /*
            public final Optional<List<MultiAddress>> bootstrapNode*;
            public final int swarmPort*;
            public final String apiAddress*, gatewayAddress*, allowTarget*;
            public final List<IpfsInstaller.Plugin> plugins;
            public final Optional<String> metricsAddress;
    */
            HostBuilder builder = new HostBuilder().generateIdentity();
            PrivKey privKey = builder.getPrivateKey();
            PeerId peerId = builder.getPeerId();

            List<io.ipfs.multiaddr.MultiAddress> swarmAddresses = List.of(new io.ipfs.multiaddr.MultiAddress("/ip4/0.0.0.0/tcp/" + configParams.swarmPort),
                    new io.ipfs.multiaddr.MultiAddress("/ip6/::/tcp/" + configParams.swarmPort));
            io.ipfs.multiaddr.MultiAddress apiAddress = new io.ipfs.multiaddr.MultiAddress(configParams.apiAddress);
            io.ipfs.multiaddr.MultiAddress gatewayAddress = new io.ipfs.multiaddr.MultiAddress(configParams.gatewayAddress);
            Optional<io.ipfs.multiaddr.MultiAddress> proxyTargetAddress = Optional.of(new io.ipfs.multiaddr.MultiAddress(proxyTarget.toString()));

            Optional<String> allowTarget = Optional.of(configParams.allowTarget);
            List<io.ipfs.multiaddr.MultiAddress> bootstrapNodes = configParams.bootstrapNode.isPresent() ? configParams.bootstrapNode.get().stream()
                    .map(b -> new io.ipfs.multiaddr.MultiAddress(b.toString()))
                    .collect(Collectors.toList()) : Collections.emptyList();

            Map<String, Object> blockChildMap = new LinkedHashMap<>();
            blockChildMap.put("path", "blocks");
            blockChildMap.put("shardFunc", "/repo/flatfs/shard/v1/next-to-last/2");
            blockChildMap.put("sync", "true");
            blockChildMap.put("type", "flatfs");
            Mount blockMount = new Mount("/blocks", "flatfs.datastore", "measure", blockChildMap);

            Map<String, Object> dataChildMap = new LinkedHashMap<>();
            dataChildMap.put("compression", "none");
            dataChildMap.put("path", "datastore");
            dataChildMap.put("type", "h2");
            Mount rootMount = new Mount("/", "h2.datastore", "measure", dataChildMap);

            AddressesSection addressesSection = new AddressesSection(swarmAddresses, apiAddress, gatewayAddress,
                    proxyTargetAddress, allowTarget);
            org.peergos.config.Filter filter = new Filter(FilterType.NONE, 0.0);
            CodecSet codecSet = CodecSet.empty();
            DatastoreSection datastoreSection = new DatastoreSection(blockMount, rootMount, filter, codecSet);
            BootstrapSection bootstrapSection = new BootstrapSection(bootstrapNodes);
            IdentitySection identity = new IdentitySection(privKey.bytes(), peerId);
            config = new org.peergos.config.Config(addressesSection, bootstrapSection, datastoreSection, identity);
            try {
                Files.write(configFilePath, config.toString().getBytes(), StandardOpenOption.CREATE);
            } catch (IOException ioe) {
                throw new IllegalStateException("Unable to write ipfs config file");
            }
        } else {
            try {
                config = Config.build(Files.readString(configFilePath));
            } catch (IOException ioe) {
                throw new IllegalStateException("Unable to write ipfs config file");
            }
            //TODO apply the overridden params and write config file
        }
        /*
        if (config.metricsAddress.isPresent()) {
            String[] parts = config.metricsAddress.get().split(":");
            runIpfsCmd("config", "--json", "Metrics.Enabled", "true");
            runIpfsCmd("config", "Metrics.Address", parts[0]);
            runIpfsCmd("config", "--json", "Metrics.Port", parts[1]);
        } else {
            runIpfsCmd("config", "--json", "Metrics.Enabled", "false");
            runIpfsCmd("config", "Metrics.Address", "localhost");
            runIpfsCmd("config", "--json", "Metrics.Port", "0");
        }*/
        return config;
    }
}
