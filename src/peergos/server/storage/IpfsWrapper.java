package peergos.server.storage;

import com.sun.net.httpserver.HttpServer;
import io.ipfs.cid.Cid;
import io.ipfs.multihash.Multihash;
import io.libp2p.core.PeerId;
import io.libp2p.core.crypto.PrivKey;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import org.peergos.*;
import org.peergos.blockstore.Blockstore;
import org.peergos.blockstore.FilteredBlockstore;
import org.peergos.blockstore.TypeLimitedBlockstore;
import org.peergos.client.RequestSender;
import org.peergos.config.*;
import org.peergos.config.Filter;
import org.peergos.net.*;
import org.peergos.protocol.http.HttpProtocol;
import peergos.server.util.*;
import peergos.server.util.Args;
import peergos.shared.io.ipfs.multiaddr.MultiAddress;
import peergos.shared.storage.*;
import peergos.shared.util.*;

import java.io.*;
import java.net.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.logging.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
        public final List<MultiAddress> bootstrapNode;
        public final int swarmPort;
        public final String apiAddress, gatewayAddress, allowTarget, proxyTarget;
        public final List<IpfsInstaller.Plugin> plugins;
        public final Optional<String> metricsAddress;

        public ConfigParams(List<MultiAddress> bootstrapNode,
                      String apiAddress,
                      String gatewayAddress,
                      String proxyTarget,
                      String allowTarget,
                      int swarmPort,
                      Optional<String> metricsAddress,
                      List<IpfsInstaller.Plugin> plugins) {
            this.bootstrapNode = bootstrapNode;
            this.apiAddress = apiAddress;
            this.gatewayAddress = gatewayAddress;
            this.proxyTarget = proxyTarget;
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

        List<MultiAddress> bootstrapNodes = args.hasArg(IPFS_BOOTSTRAP_NODES)
                && args.getArg(IPFS_BOOTSTRAP_NODES).trim().length() > 0 ?
                new ArrayList<>(parseMultiAddresses(args.getArg(IPFS_BOOTSTRAP_NODES))) :
                new ArrayList<>();

        int swarmPort = args.getInt("ipfs-swarm-port", 4001);
        /*
        Optional<MultiAddress> pkiNode = args.hasArg("pki-node-id") ?
                Optional.of(new MultiAddress("/ip4/127.0.0.1/tcp/" + swarmPort + "/ipfs/"+ args.getArg("pki-node-id")))
                : Optional.empty();
        if (pkiNode.isPresent()) {
            if (!bootstrapNodes.contains(pkiNode.get())) {
                bootstrapNodes.add(pkiNode.get());
            }
        }*/
        String apiAddress = args.getArg("ipfs-api-address");
        String gatewayAddress = args.getArg("ipfs-gateway-address");

        String proxyTarget = args.getArg("proxy-target");
        MultiAddress allowTarget = new MultiAddress(args.getArg("allow-target"));

        boolean enableMetrics = args.getBoolean("collect-metrics", false);
        Optional<String> metricsAddress = enableMetrics ?
                Optional.of(args.getArg("metrics.address") + ":" + args.getInt("ipfs.metrics.port")) :
                Optional.empty();
        List<IpfsInstaller.Plugin> plugins = IpfsInstaller.Plugin.parseAll(args);


        return new ConfigParams(bootstrapNodes, apiAddress, gatewayAddress,
                proxyTarget,
                "http://" + allowTarget.getHost() + ":" + allowTarget.getTCPPort(),
                swarmPort, metricsAddress, plugins);
    }

    private static final String IPFS_DIR = "IPFS_PATH";
    private static final String DEFAULT_DIR_NAME = ".ipfs";

    public final Path ipfsDir;
    public final ConfigParams configParams;

    private EmbeddedIpfs embeddedIpfs;
    private HttpServer apiServer;
    private HttpServer p2pServer;

    private static final Map<Integer, IdentitySection> ipfsSwarmPortToIdentity = new HashMap<>();

    public IpfsWrapper(Path ipfsDir, ConfigParams configParams) {

        File ipfsDirF = ipfsDir.toFile();
        if (! ipfsDirF.isDirectory() && ! ipfsDirF.mkdirs()) {
            throw new IllegalStateException("Specified IPFS_PATH '" + ipfsDir + " is not a directory and/or could not be created");
        }

        this.ipfsDir = ipfsDir;
        this.configParams = configParams;
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
            apiServer.stop(0);
            p2pServer.stop(0);
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
        return new IpfsWrapper(ipfsDir, configParams);
    }

    public static IpfsWrapper launch(IpfsWrapper ipfsWrapper) {
        Config config = ipfsWrapper.configure();
        LOG.info("Starting Nabu version: " + APIHandler.CURRENT_VERSION);
        BlockRequestAuthoriser authoriser = (c, b, p, a) -> CompletableFuture.completedFuture(true);

        ipfsWrapper.embeddedIpfs = EmbeddedIpfs.build(ipfsWrapper.ipfsDir,
                EmbeddedIpfs.buildBlockStore(config, ipfsWrapper.ipfsDir),
                config.addresses.getSwarmAddresses(),
                config.bootstrap.getBootstrapAddresses(),
                config.identity,
                authoriser,
                config.addresses.proxyTargetAddress.map(IpfsWrapper::proxyHandler)
        );
        ipfsWrapper.embeddedIpfs.start();
        io.ipfs.multiaddr.MultiAddress apiAddress = config.addresses.apiAddress;
        InetSocketAddress localAPIAddress = new InetSocketAddress(apiAddress.getHost(), apiAddress.getPort());

        int maxConnectionQueue = 500;
        int handlerThreads = 50;
        LOG.info("Starting RPC API server at " + apiAddress.getHost() + ":" + localAPIAddress.getPort());
        try {
            ipfsWrapper.apiServer = HttpServer.create(localAPIAddress, maxConnectionQueue);
            ipfsWrapper.apiServer.createContext(APIHandler.API_URL, new APIHandler(ipfsWrapper.embeddedIpfs));
            ipfsWrapper.apiServer.setExecutor(Executors.newFixedThreadPool(handlerThreads));
            ipfsWrapper.apiServer.start();

            io.ipfs.multiaddr.MultiAddress p2pAddress = config.addresses.gatewayAddress;
            InetSocketAddress localP2pAddress = new InetSocketAddress(p2pAddress.getHost(), p2pAddress.getPort());
            ipfsWrapper.p2pServer = HttpServer.create(localP2pAddress, maxConnectionQueue);

            ipfsWrapper.p2pServer.createContext(HttpProxyService.API_URL, new HttpProxyHandler(
                    new HttpProxyService(ipfsWrapper.embeddedIpfs.node, ipfsWrapper.embeddedIpfs.p2pHttp.get(),
                            ipfsWrapper.embeddedIpfs.dht)));
            ipfsWrapper.p2pServer.setExecutor(Executors.newFixedThreadPool(handlerThreads));
            ipfsWrapper.p2pServer.start();
        } catch (IOException ioe) {
            throw new IllegalStateException("Unable to start Server: " + ioe);
        }
        Thread shutdownHook = new Thread(() -> {
            ipfsWrapper.stop();
        });
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        return ipfsWrapper;
    }

    public static class MemBlockstore implements Blockstore {
        private final ConcurrentHashMap<Cid, byte[]> blocks = new ConcurrentHashMap();

        public MemBlockstore() {
        }

        @Override
        public CompletableFuture<Boolean> hasAny(Multihash h) {
            return Futures.of(Stream.of(Cid.Codec.DagCbor, Cid.Codec.Raw, Cid.Codec.DagProtobuf)
                    .anyMatch(c -> has(new Cid(1, c, h.getType(), h.getHash())).join()));
        }

        public CompletableFuture<Boolean> has(Cid c) {
            return CompletableFuture.completedFuture(this.blocks.containsKey(c));
        }

        public CompletableFuture<Optional<byte[]>> get(Cid c) {
            return CompletableFuture.completedFuture(Optional.ofNullable((byte[])this.blocks.get(c)));
        }

        public CompletableFuture<Cid> put(byte[] block, Cid.Codec codec) {
            Cid cid = new Cid(1L, codec, io.ipfs.multihash.Multihash.Type.sha2_256, Hash.sha256(block));
            this.blocks.put(cid, block);
            return CompletableFuture.completedFuture(cid);
        }

        public CompletableFuture<Boolean> rm(Cid c) {
            if (this.blocks.containsKey(c)) {
                this.blocks.remove(c);
                return CompletableFuture.completedFuture(true);
            } else {
                return CompletableFuture.completedFuture(false);
            }
        }

        public CompletableFuture<Boolean> bloomAdd(Cid cid) {
            return CompletableFuture.completedFuture(false);
        }

        public CompletableFuture<List<Cid>> refs() {
            return CompletableFuture.completedFuture(new ArrayList(this.blocks.keySet()));
        }
    }

    public static Blockstore buildMemoryBlockStore(Config config, Path path) {
        Blockstore blocks = new MemBlockstore();
        Object blockStore;
        if (config.datastore.filter.type == FilterType.BLOOM) {
            blockStore = FilteredBlockstore.bloomBased(blocks, config.datastore.filter.falsePositiveRate);
        } else if (config.datastore.filter.type == FilterType.INFINI) {
            blockStore = FilteredBlockstore.infiniBased(blocks, config.datastore.filter.falsePositiveRate);
        } else {
            if (config.datastore.filter.type != FilterType.NONE) {
                throw new IllegalStateException("Unhandled filter type: " + config.datastore.filter.type);
            }
            blockStore = blocks;
        }
        return (Blockstore)(config.datastore.allowedCodecs.codecs.isEmpty() ? blockStore : new TypeLimitedBlockstore((Blockstore)blockStore, config.datastore.allowedCodecs.codecs));
    }


    public Config configure() {
        Config config = null;
        Path configFilePath = ipfsDir.resolve("config");
        LOG().info("Initializing ipfs");
    /*
            public final Optional<List<MultiAddress>> bootstrapNode*;
            public final int swarmPort*;
            public final String apiAddress*, gatewayAddress*, allowTarget*;
            public final List<IpfsInstaller.Plugin> plugins;
            public final Optional<String> metricsAddress;
    */
        IdentitySection identity = ipfsSwarmPortToIdentity.get(configParams.swarmPort);
        if (identity == null) {
            HostBuilder builder = new HostBuilder().generateIdentity();
            PrivKey privKey = builder.getPrivateKey();
            PeerId peerId = builder.getPeerId();
            identity = new IdentitySection(privKey.bytes(), peerId);
            ipfsSwarmPortToIdentity.put(configParams.swarmPort, identity);
        }

        List<io.ipfs.multiaddr.MultiAddress> swarmAddresses = List.of(new io.ipfs.multiaddr.MultiAddress("/ip6/::/tcp/" + configParams.swarmPort));
        io.ipfs.multiaddr.MultiAddress apiAddress = new io.ipfs.multiaddr.MultiAddress(configParams.apiAddress);
        io.ipfs.multiaddr.MultiAddress gatewayAddress = new io.ipfs.multiaddr.MultiAddress(configParams.gatewayAddress);
        Optional<io.ipfs.multiaddr.MultiAddress> proxyTargetAddress = Optional.of(new io.ipfs.multiaddr.MultiAddress(configParams.proxyTarget));

        Optional<String> allowTarget = Optional.of(configParams.allowTarget);
        List<io.ipfs.multiaddr.MultiAddress> bootstrapNodes = configParams.bootstrapNode.stream()
                .map(b -> new io.ipfs.multiaddr.MultiAddress(b.toString()))
                .collect(Collectors.toList());

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
        config = new org.peergos.config.Config(addressesSection, bootstrapSection, datastoreSection, identity);
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
