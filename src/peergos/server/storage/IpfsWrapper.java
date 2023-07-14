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
import org.peergos.net.*;
import org.peergos.protocol.http.HttpProtocol;
import org.peergos.util.JSONParser;
import org.peergos.util.JsonHelper;
import peergos.server.Command;
import peergos.server.util.*;
import peergos.server.util.Args;
import peergos.shared.io.ipfs.cid.Cid;
import peergos.shared.io.ipfs.multiaddr.MultiAddress;
import peergos.shared.io.ipfs.multibase.Base58;
import peergos.shared.io.ipfs.multihash.Multihash;
import peergos.shared.storage.*;
import peergos.shared.util.*;

import java.io.*;
import java.net.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
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

    public static class S3ConfigParams {
        public final String s3Path, s3Bucket, s3Region, s3AccessKey, s3SecretKey, s3RegionEndpoint;

        private S3ConfigParams(String s3Path,
                              String s3Bucket,
                              String s3Region,
                              String s3AccessKey,
                              String s3SecretKey,
                              String s3RegionEndpoint) {
            this.s3Path = s3Path;
            this.s3Bucket = s3Bucket;
            this.s3Region = s3Region;
            this.s3AccessKey = s3AccessKey;
            this.s3SecretKey = s3SecretKey;
            this.s3RegionEndpoint = s3RegionEndpoint;
        }
        public static S3ConfigParams build(Optional<String> s3Path,
            Optional<String> s3Bucket,
            Optional<String> s3Region,
            Optional<String> s3AccessKey,
            Optional<String> s3SecretKey,
            Optional<String> s3RegionEndpoint) {
            S3ConfigParams params = new S3ConfigParams(s3Path.orElse(""), s3Bucket.orElse(""), s3Region.orElse(""),
                        s3AccessKey.orElse(""), s3SecretKey.orElse(""), s3RegionEndpoint.orElse(""));
            return params;
        }
    }
    public static class IpfsConfigParams {
        /**
         * Encapsulate IPFS configuration state.
         */
        public final List<MultiAddress> bootstrapNode;
        public final int swarmPort;
        public final String apiAddress, gatewayAddress, allowTarget, proxyTarget;
        public final Optional<String> metricsAddress;
        public final  Optional<S3ConfigParams> s3ConfigParams;

        public IpfsConfigParams(List<MultiAddress> bootstrapNode,
                                String apiAddress,
                                String gatewayAddress,
                                String proxyTarget,
                                String allowTarget,
                                int swarmPort,
                                Optional<String> metricsAddress,
                                Optional<S3ConfigParams> s3ConfigParams) {
            this.bootstrapNode = bootstrapNode;
            this.apiAddress = apiAddress;
            this.gatewayAddress = gatewayAddress;
            this.proxyTarget = proxyTarget;
            this.allowTarget = allowTarget;
            this.swarmPort = swarmPort;
            this.metricsAddress = metricsAddress;
            this.s3ConfigParams = s3ConfigParams;
        }
    }

    private static List<MultiAddress> parseMultiAddresses(String s) {
        return Stream.of(s.split(","))
                .filter(e -> ! e.isEmpty())
                .map(MultiAddress::new)
                .collect(Collectors.toList());
    }

    public static IpfsConfigParams buildConfig(Args args) {

        List<MultiAddress> bootstrapNodes = args.hasArg(IPFS_BOOTSTRAP_NODES)
                && args.getArg(IPFS_BOOTSTRAP_NODES).trim().length() > 0 ?
                new ArrayList<>(parseMultiAddresses(args.getArg(IPFS_BOOTSTRAP_NODES))) :
                new ArrayList<>();

        int swarmPort = args.getInt("ipfs-swarm-port", 4001);

        String apiAddress = args.getArg("ipfs-api-address");
        String gatewayAddress = args.getArg("ipfs-gateway-address");

        String proxyTarget = args.getArg("proxy-target");
        MultiAddress allowTarget = new MultiAddress(args.getArg("allow-target"));

        boolean enableMetrics = args.getBoolean("collect-metrics", false);
        Optional<String> metricsAddress = enableMetrics ?
                Optional.of(args.getArg("metrics.address") + ":" + args.getInt("ipfs.metrics.port")) :
                Optional.empty();


        Optional<S3ConfigParams> s3Params = S3Config.useS3(args) ?
            Optional.of(
                S3ConfigParams.build(args.getOptionalArg("s3.path") , args.getOptionalArg("s3.bucket"),
                args.getOptionalArg("s3.region"), args.getOptionalArg("s3.accessKey"), args.getOptionalArg("s3.secretKey"),
                args.getOptionalArg("s3.region.endpoint"))
            ) : Optional.empty();
        return new IpfsConfigParams(bootstrapNodes, apiAddress, gatewayAddress,
                proxyTarget,
                "http://" + allowTarget.getHost() + ":" + allowTarget.getTCPPort(),
                swarmPort, metricsAddress, s3Params);
    }

    private static final String IPFS_DIR = "IPFS_PATH";
    private static final String DEFAULT_DIR_NAME = ".ipfs";

    public final Path ipfsDir;
    public final IpfsConfigParams ipfsConfigParams;

    private EmbeddedIpfs embeddedIpfs;
    private HttpServer apiServer;
    private HttpServer p2pServer;

    private static final Map<Integer, IdentitySection> ipfsSwarmPortToIdentity = new HashMap<>();

    public IpfsWrapper(Path ipfsDir, IpfsConfigParams ipfsConfigParams) {

        File ipfsDirF = ipfsDir.toFile();
        if (! ipfsDirF.isDirectory() && ! ipfsDirF.mkdirs()) {
            throw new IllegalStateException("Specified IPFS_PATH '" + ipfsDir + " is not a directory and/or could not be created");
        }

        this.ipfsDir = ipfsDir;
        this.ipfsConfigParams = ipfsConfigParams;
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

    public static IpfsWrapper launch(Args args) {

        Path ipfsDir = getIpfsDir(args);
        LOG().info("Using IPFS dir " + ipfsDir);
        IpfsConfigParams ipfsConfigParams = buildConfig(args);
        IpfsWrapper ipfsWrapper = new IpfsWrapper(ipfsDir, ipfsConfigParams);

        Config config = ipfsWrapper.configure();
        LOG.info("Starting Nabu version: " + APIHandler.CURRENT_VERSION);
        BlockRequestAuthoriser authoriser = (c, b, p, a) -> {
            if (config.addresses.allowTarget.isEmpty()) {
                CompletableFuture.completedFuture(false);
            }
            try {
                Multihash hash = Multihash.decode(p.bareMultihash().toBytes());
                Cid peer = new Cid(1, Cid.Codec.LibP2pKey, hash.type, hash.getHash());
                String uri = config.addresses.allowTarget.get() + "?cid=" + c.toString() + "&peer=" + peer.toString() + "&auth=" + a;
                Map<String, String> fields = new HashMap<>();
                fields.put("Host", peer.toString());
                byte[] resp = org.peergos.util.HttpUtil.post(uri, fields, b);
                return CompletableFuture.completedFuture((new String(resp)).equals("true"));
            } catch (IOException ioe) {
                return CompletableFuture.completedFuture(false);
            }
        };


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

    private Config configure() {

        LOG().info("Initializing ipfs");
        IdentitySection identity = ipfsSwarmPortToIdentity.get(ipfsConfigParams.swarmPort);
        if (identity == null) {
            HostBuilder builder = new HostBuilder().generateIdentity();
            PrivKey privKey = builder.getPrivateKey();
            PeerId peerId = builder.getPeerId();
            identity = new IdentitySection(privKey.bytes(), peerId);
            ipfsSwarmPortToIdentity.put(ipfsConfigParams.swarmPort, identity);
        }

        List<io.ipfs.multiaddr.MultiAddress> swarmAddresses = List.of(new io.ipfs.multiaddr.MultiAddress("/ip6/::/tcp/" + ipfsConfigParams.swarmPort));
        io.ipfs.multiaddr.MultiAddress apiAddress = new io.ipfs.multiaddr.MultiAddress(ipfsConfigParams.apiAddress);
        io.ipfs.multiaddr.MultiAddress gatewayAddress = new io.ipfs.multiaddr.MultiAddress(ipfsConfigParams.gatewayAddress);
        Optional<io.ipfs.multiaddr.MultiAddress> proxyTargetAddress = Optional.of(new io.ipfs.multiaddr.MultiAddress(ipfsConfigParams.proxyTarget));

        Optional<String> allowTarget = Optional.of(ipfsConfigParams.allowTarget);
        List<io.ipfs.multiaddr.MultiAddress> bootstrapNodes = ipfsConfigParams.bootstrapNode.stream()
                .map(b -> new io.ipfs.multiaddr.MultiAddress(b.toString()))
                .collect(Collectors.toList());

        Map<String, Object> blockChildMap = new LinkedHashMap<>();
        if (ipfsConfigParams.s3ConfigParams.isPresent()) {
            S3ConfigParams s3Params = ipfsConfigParams.s3ConfigParams.get();
            blockChildMap.put("region", s3Params.s3RegionEndpoint);
            blockChildMap.put("bucket", s3Params.s3Bucket);
            blockChildMap.put("rootDirectory", s3Params.s3Path);
            blockChildMap.put("regionEndpoint", s3Params.s3RegionEndpoint);
            blockChildMap.put("accessKey", s3Params.s3AccessKey);
            blockChildMap.put("secretKey", s3Params.s3SecretKey);
            blockChildMap.put("type", "s3ds");
        } else {
            blockChildMap.put("path", "blocks");
            blockChildMap.put("shardFunc", "/repo/flatfs/shard/v1/next-to-last/2");
            blockChildMap.put("sync", "true");
            blockChildMap.put("type", "flatfs");
        }
        String prefix = ipfsConfigParams.s3ConfigParams.isPresent() ? "s3.datastore" : "flatfs.datastore";
        Mount blockMount = new Mount("/blocks", prefix, "measure", blockChildMap);;

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
        Config config = new org.peergos.config.Config(addressesSection, bootstrapSection, datastoreSection, identity);
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
