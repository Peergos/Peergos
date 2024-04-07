package peergos.server.storage;

import com.sun.net.httpserver.HttpServer;
import io.ipfs.cid.Cid;
import io.libp2p.core.PeerId;
import io.libp2p.core.crypto.*;
import org.peergos.*;
import org.peergos.config.*;
import org.peergos.config.Filter;
import org.peergos.net.*;
import org.peergos.protocol.dht.DatabaseRecordStore;
import org.peergos.protocol.http.HttpProtocol;
import org.peergos.util.JSONParser;
import peergos.server.*;
import peergos.server.AggregatedMetrics;
import peergos.server.sql.SqlSupplier;
import peergos.server.storage.auth.JdbcBatCave;
import peergos.server.util.*;
import peergos.server.util.Args;
import peergos.server.storage.auth.BlockRequestAuthoriser;
import peergos.shared.*;
import peergos.shared.crypto.hash.Hasher;
import peergos.shared.io.ipfs.*;
import peergos.shared.storage.*;
import peergos.shared.storage.auth.*;
import peergos.shared.util.*;

import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.*;
import java.util.function.Supplier;
import java.util.logging.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static peergos.server.util.Logging.LOG;
import static peergos.server.util.AddressUtil.getAddress;

public class IpfsWrapper implements AutoCloseable {

    private static final Logger LOG = Logger.getGlobal();

    public static final String IPFS_BOOTSTRAP_NODES = "ipfs-config-bootstrap-node-list";
    public static final String DEFAULT_BOOTSTRAP_LIST = Stream.of(
            "/ip4/172.104.157.121/tcp/4001/p2p/QmVdFZgHnEgcedCS2G2ZNiEN59LuVrnRm7z3yXtEBv2XiF",
            "/ip6/2a01:7e01::f03c:92ff:fe26:f671/tcp/4001/p2p/QmVdFZgHnEgcedCS2G2ZNiEN59LuVrnRm7z3yXtEBv2XiF",
            "/ip4/172.104.143.23/tcp/4001/p2p/12D3KooWFv6ZcoUKyaDBB7nR5SQg6HpmEbDXad48WyFSyEk7xrSR",
            "/ip6/2a01:7e01::f03c:92ff:fee5:154a/tcp/4001/p2p/12D3KooWFv6ZcoUKyaDBB7nR5SQg6HpmEbDXad48WyFSyEk7xrSR",
            "/dnsaddr/bootstrap.libp2p.io/p2p/QmNnooDu7bfjPFoTZYxMNLWUQJyrVwtbZg5gBMjTezGAJN",
            "/dnsaddr/bootstrap.libp2p.io/p2p/QmQCU2EcMqAqQPR2i9bChDtGNJchTbq5TbXJJ16u19uLTa",
            "/dnsaddr/bootstrap.libp2p.io/p2p/QmbLHAnMoJPWSCR5Zhtx6BHJX9KiKNN6tpvbUcqanj75Nb",
            "/dnsaddr/bootstrap.libp2p.io/p2p/QmcZf59bWwK5XFi76CZX8cbJ4BhTzzA3gU1ZjYZcYW3dwt",
            "/ip4/104.131.131.82/tcp/4001/p2p/QmaCpDMGvV2BGHeYERUEnRQAwe3N8SzbUtfsmvsqQLuvuJ",
            "/ip4/104.236.179.241/tcp/4001/p2p/QmSoLPppuBtQSGwKDZT2M73ULpjvfd3aZ6ha4oFGL1KrGM",
            "/ip4/128.199.219.111/tcp/4001/p2p/QmSoLSafTMBsPKadTEgaXctDQVcqN88CNLHXMkTNwMKPnu",
            "/ip4/104.236.76.40/tcp/4001/p2p/QmSoLV4Bbm51jM9C4gDYZQ9Cy3U6aXMJDAbzgu2fzaDs64",
            "/ip4/178.62.158.247/tcp/4001/p2p/QmSoLer265NRgSp2LA3dPaeykiS1J6DifTC88f5uVQKNAd",
            "/ip6/2604:a880:1:20::203:d001/tcp/4001/p2p/QmSoLPppuBtQSGwKDZT2M73ULpjvfd3aZ6ha4oFGL1KrGM",
            "/ip6/2400:6180:0:d0::151:6001/tcp/4001/p2p/QmSoLSafTMBsPKadTEgaXctDQVcqN88CNLHXMkTNwMKPnu",
            "/ip6/2604:a880:800:10::4a:5001/tcp/4001/p2p/QmSoLV4Bbm51jM9C4gDYZQ9Cy3U6aXMJDAbzgu2fzaDs64",
            "/ip6/2a03:b0c0:0:1010::23:1001/tcp/4001/p2p/QmSoLer265NRgSp2LA3dPaeykiS1J6DifTC88f5uVQKNAd",
            "/ip4/104.131.131.82/udp/4001/quic/p2p/QmaCpDMGvV2BGHeYERUEnRQAwe3N8SzbUtfsmvsqQLuvuJ"
    ).collect(Collectors.joining(","));

    private static HttpProtocol.HttpRequestProcessor proxyHandler(io.ipfs.multiaddr.MultiAddress target) {
        return (s, req, h) -> HttpProtocol.proxyRequest(req, convert(target), h);
    }

    private static SocketAddress convert(io.ipfs.multiaddr.MultiAddress target) {
        return new InetSocketAddress(target.getHost(), target.getPort());
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
        public final String apiAddress, gatewayAddress, proxyTarget;
        public final boolean enableMetrics;
        public final Optional<String> metricsAddress;
        public final Optional<Integer> metricsPort;
        public final Optional<S3ConfigParams> s3ConfigParams;
        public final Optional<IdentitySection> identity;
        public final Filter blockFilter;

        public IpfsConfigParams(List<MultiAddress> bootstrapNode,
                                String apiAddress,
                                String gatewayAddress,
                                String proxyTarget,
                                int swarmPort,
                                boolean enableMetrics,
                                Optional<String> metricsAddress,
                                Optional<Integer> metricsPort,
                                Optional<S3ConfigParams> s3ConfigParams,
                                Filter blockFilter,
                                Optional<IdentitySection> identity) {
            this.bootstrapNode = bootstrapNode;
            this.apiAddress = apiAddress;
            this.gatewayAddress = gatewayAddress;
            this.proxyTarget = proxyTarget;
            this.swarmPort = swarmPort;
            this.enableMetrics = enableMetrics;
            this.metricsAddress = metricsAddress;
            this.metricsPort = metricsPort;
            this.s3ConfigParams = s3ConfigParams;
            this.blockFilter = blockFilter;
            this.identity = identity;
        }
        public IpfsConfigParams withIdentity(Optional<IdentitySection> identity) {
            return new IpfsConfigParams(this.bootstrapNode, this.apiAddress, this.gatewayAddress, this.proxyTarget,
                    this.swarmPort, this.enableMetrics, this.metricsAddress, this.metricsPort, this.s3ConfigParams, this.blockFilter,
                    identity);
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
                parseMultiAddresses(DEFAULT_BOOTSTRAP_LIST);

        int swarmPort = args.getInt("ipfs-swarm-port", 4001);

        String apiAddress = args.getArg("ipfs-api-address");
        String gatewayAddress = args.getArg("ipfs-gateway-address");

        String proxyTarget = args.getArg("proxy-target");
        boolean enableMetrics = args.getBoolean("collect-metrics", false);
        Optional<String> metricsAddress = args.getOptionalArg("metrics.address");
        Optional<Integer> metricsPort = args.getOptionalArg("ipfs.metrics.port").map(Integer::parseInt);

        Optional<S3ConfigParams> s3Params = S3Config.useS3(args) ?
            Optional.of(
                S3ConfigParams.build(args.getOptionalArg("s3.path") , args.getOptionalArg("s3.bucket"),
                args.getOptionalArg("s3.region"), args.getOptionalArg("s3.accessKey"), args.getOptionalArg("s3.secretKey"),
                args.getOptionalArg("s3.region.endpoint"))
            ) : Optional.empty();
        Optional<IdentitySection> peergosIdentity = Optional.empty();
        if (args.hasArg("ipfs.identity.priv-key") && args.hasArg("ipfs.identity.peerid")) {
            LOG.info("Using identity provided via command arguments");
            peergosIdentity = Optional.of(new IdentitySection(
                    io.ipfs.multibase.binary.Base64.decodeBase64(args.getArg("ipfs.identity.priv-key")),
                    PeerId.fromBase58(args.getArg("ipfs.identity.peerid")))
            );
        }

        Optional<String> blockStoreFilterOpt = args.getOptionalArg("block-store-filter");
        Filter filter = new Filter(FilterType.NONE, 0.0);
        if (blockStoreFilterOpt.isPresent()) {
            String blockStoreFilterName = blockStoreFilterOpt.get().toLowerCase().trim();
            FilterType type = FilterType.NONE;
            try {
                type = FilterType.lookup(blockStoreFilterName);
            } catch (IllegalArgumentException iae) {
                LOG.warning("Provided block-store-filter parameter is invalid. Defaulting to no filter");
            }
            Optional<String> blockStoreFilterFalsePositiveRateOpt = args.getOptionalArg("block-store-filter-false-positive-rate");
            Double falsePositiveRate = 0.0;
            if (blockStoreFilterFalsePositiveRateOpt.isPresent()) {
                String blockStoreFilterFalsePositiveRateStr = blockStoreFilterFalsePositiveRateOpt.get().trim();
                try {
                    falsePositiveRate = Double.parseDouble(blockStoreFilterFalsePositiveRateStr);
                } catch (NumberFormatException nfe) {
                    LOG.warning("Provided block-store-filter-false-positive-rate parameter is invalid. Defaulting to no filter");
                    type = FilterType.NONE;
                }
            }
            filter = new Filter(type, falsePositiveRate);
        }
        return new IpfsConfigParams(bootstrapNodes, apiAddress, gatewayAddress,
                proxyTarget, swarmPort, enableMetrics, metricsAddress, metricsPort, s3Params, filter, peergosIdentity);
    }

    private static final String IPFS_DIR = "IPFS_PATH";
    private static final String DEFAULT_DIR_NAME = ".ipfs";

    public final Path ipfsDir;
    public final IpfsConfigParams ipfsConfigParams;

    private EmbeddedIpfs embeddedIpfs;
    private HttpServer apiServer;
    private HttpServer p2pServer;
    private volatile boolean running = true;

    public IpfsWrapper(Path ipfsDir, IpfsConfigParams ipfsConfigParams) {
        File ipfsDirF = ipfsDir.toFile();
        if (! ipfsDirF.isDirectory() && ! ipfsDirF.mkdirs()) {
            throw new IllegalStateException("Specified IPFS_PATH '" + ipfsDir + " is not a directory and/or could not be created");
        }
        this.ipfsDir = ipfsDir;
        Optional<IdentitySection> identityOpt = Optional.empty();
        if (ipfsConfigParams.identity.isPresent()) {
            identityOpt = ipfsConfigParams.identity;
        } else {
            identityOpt = readIPFSIdentity(ipfsDir);
            if (identityOpt.isEmpty()) {
                LOG.info("Creating new identity");
                HostBuilder builder = new HostBuilder().generateIdentity();
                PrivKey privKey = builder.getPrivateKey();
                PeerId peerId = builder.getPeerId();
                identityOpt = Optional.of(new IdentitySection(privKey.bytes(), peerId));
            }
        }
        this.ipfsConfigParams= ipfsConfigParams.withIdentity(identityOpt);
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
            running = false;
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

    private static Optional<IdentitySection> readIPFSIdentity(Path ipfsDir) {
        Path configFilePath = ipfsDir.resolve("config");
        File configFile = configFilePath.toFile();
        if (!configFile.exists()) {
            return Optional.empty();
        }
        try {
            Map<String, Object> json = (Map) JSONParser.parse(Files.readString(configFilePath));
            IdentitySection identitySection = Jsonable.parse(json, p -> IdentitySection.fromJson(p));
            LOG.info("Using identity found in config file from folder: " + ipfsDir);
            return Optional.of(identitySection);
        }  catch (IOException ioe) {
            return Optional.empty();
        }
    }

    public static IpfsWrapper launch(Args args) {
        SqlSupplier sqlCommands = Builder.getSqlCommands(args);
        Supplier<Connection> dbConn = Builder.getDBConnector(args, "bat-store");
        BatCave batStore = new JdbcBatCave(dbConn, sqlCommands);
        Crypto crypto = Builder.initCrypto();
        Hasher hasher = crypto.hasher;
        BlockRequestAuthoriser blockAuth = Builder.blockAuthoriser(args, batStore, hasher);
        BlockMetadataStore metaDB = Builder.buildBlockMetadata(args);
        JdbcServerIdentityStore ids = JdbcServerIdentityStore.build(Builder.getDBConnector(args, "serverids-file"), sqlCommands, crypto);
        return launch(args, blockAuth, metaDB, ids);
    }

    private void startIdPublisher(ServerIdentityStore ids) {
        Thread publisher = new Thread(() -> {
            while (running) {
                try {
                    List<PeerId> all = ids.getIdentities();
                    for (PeerId id : all) {
                        byte[] signedIpnsRecord = ids.getRecord(id);
                        embeddedIpfs.publishPresignedRecord(io.ipfs.multihash.Multihash.deserialize(id.getBytes()), signedIpnsRecord).join();
                    }
                } catch (Exception e) {
                    LOG.log(Level.SEVERE, e, e::getMessage);
                }
                try {
                    Thread.sleep(6 * 3600 * 1000);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "Server Identity IPNS Publisher");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            publisher.interrupt();
        }, "Server Identity IPNS Publisher - shutdown"));
        publisher.setDaemon(true);
        publisher.start();
    }

    public static IpfsWrapper launch(Args args, BlockRequestAuthoriser blockAuth, BlockMetadataStore metaDB,
                                     ServerIdentityStore ids) {
        Path ipfsDir = getIpfsDir(args);
        LOG.info("Using IPFS dir " + ipfsDir);

        IpfsConfigParams ipfsConfigParams = buildConfig(args);

        IpfsWrapper ipfsWrapper = new IpfsWrapper(ipfsDir, ipfsConfigParams);
        Config config = ipfsWrapper.configure();
        // use identity from db if present, otherwise move to db
        List<PeerId> ourIds = ids.getIdentities();
        if (ourIds.isEmpty()) {
            // initialise id db with our current peerid and sign an ipns record
            PrivKey peerPrivate = KeyKt.unmarshalPrivateKey(config.identity.privKeyProtobuf);
            byte[] signedRecord = ServerIdentity.generateSignedIpnsRecord(peerPrivate, Optional.empty(), false, 1);
            ids.addIdentity(PeerId.fromPubKey(peerPrivate.publicKey()), signedRecord);
        } else {
            // make sure we are using the latest identity
            PeerId current = ourIds.get(ourIds.size() - 1);
            if (! current.equals(config.identity.peerId))
                throw new IllegalStateException("Supplied peerid ("+config.identity.peerId.toBase58()+
                        ") doesn't match latest in server identity db ("+current.toBase58()+")!");
        }

        LOG.info("Starting Nabu version: " + APIHandler.CURRENT_VERSION + ", peerid: " + config.identity.peerId);
        org.peergos.BlockRequestAuthoriser authoriser = (c, p, auth) -> {
            peergos.shared.io.ipfs.Cid source = peergos.shared.io.ipfs.Cid.decodePeerId(p.toString());
            peergos.shared.io.ipfs.Cid cid = peergos.shared.io.ipfs.Cid.decode(c.toString());
            Optional<BlockMetadata> blockMetadata = metaDB.get(cid);
            if (blockMetadata.isEmpty())
                return Futures.of(false);
            List<BatId> bats = blockMetadata.get().batids;
            return blockAuth.allowRead(cid, bats, source, auth)
                .exceptionally(ex -> false);
        };

        Path datastorePath = ipfsWrapper.ipfsDir.resolve("datastore").resolve("h2-v2.datastore");
        DatabaseRecordStore records = new DatabaseRecordStore(datastorePath.toAbsolutePath().toString());

        org.peergos.blockstore.metadatadb.BlockMetadataStore meta =
                new DelegatingBlockMetadataStore(metaDB);
        boolean provideBlocks = args.hasArg("mirror.node.id") || args.hasArg("mirror.username");
        ipfsWrapper.embeddedIpfs = EmbeddedIpfs.build(records,
                EmbeddedIpfs.buildBlockStore(config, ipfsWrapper.ipfsDir, meta, false),
                provideBlocks,
                config.addresses.getSwarmAddresses(),
                config.bootstrap.getBootstrapAddresses(),
                config.identity,
                authoriser,
                config.addresses.proxyTargetAddress.map(IpfsWrapper::proxyHandler),
                Optional.of("/peergos/bitswap"),
                Optional.empty()
        );
        ipfsWrapper.embeddedIpfs.start();
        io.ipfs.multiaddr.MultiAddress apiAddress = config.addresses.apiAddress;
        InetSocketAddress localAPIAddress = new InetSocketAddress(apiAddress.getHost(), apiAddress.getPort());

        int maxConnectionQueue = 500;
        int handlerThreads = 50;
        LOG.info("Starting Nabu API server at " + apiAddress.getHost() + ":" + localAPIAddress.getPort());
        try {
            if (config.metrics.enabled) {
                LOG.info("Starting ipfs metrics endpoint at " + config.metrics.address + ":" + config.metrics.port);
                AggregatedMetrics.startExporter(config.metrics.address, config.metrics.port);
            }

            ipfsWrapper.apiServer = HttpServer.create(localAPIAddress, maxConnectionQueue);
            ipfsWrapper.apiServer.createContext(APIHandler.API_URL, new APIHandler(ipfsWrapper.embeddedIpfs));
            ipfsWrapper.apiServer.setExecutor(Threads.newPool(handlerThreads, "Nabu-api-handler-"));
            ipfsWrapper.apiServer.start();

            io.ipfs.multiaddr.MultiAddress p2pAddress = config.addresses.gatewayAddress;
            InetSocketAddress localP2pAddress = new InetSocketAddress(p2pAddress.getHost(), p2pAddress.getPort());
            ipfsWrapper.p2pServer = HttpServer.create(localP2pAddress, maxConnectionQueue);

            ipfsWrapper.p2pServer.createContext(HttpProxyService.API_URL, new HttpProxyHandler(
                    new HttpProxyService(ipfsWrapper.embeddedIpfs.node, ipfsWrapper.embeddedIpfs.p2pHttp.get(),
                            ipfsWrapper.embeddedIpfs.dht)));
            ipfsWrapper.p2pServer.setExecutor(Threads.newPool(handlerThreads, "Nabu-proxy-handler-"));
            ipfsWrapper.p2pServer.start();
        } catch (IOException ioe) {
            throw new IllegalStateException("Unable to start Server: " + ioe);
        }
        Thread shutdownHook = new Thread(ipfsWrapper::stop);
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        ipfsWrapper.startIdPublisher(ids);
        return ipfsWrapper;
    }

    private Config configure() {

        LOG().info("Initializing ipfs");
        IdentitySection identity = ipfsConfigParams.identity.get();

        List<io.ipfs.multiaddr.MultiAddress> swarmAddresses = List.of(new io.ipfs.multiaddr.MultiAddress("/ip6/::/tcp/" + ipfsConfigParams.swarmPort));
        io.ipfs.multiaddr.MultiAddress apiAddress = new io.ipfs.multiaddr.MultiAddress(ipfsConfigParams.apiAddress);
        io.ipfs.multiaddr.MultiAddress gatewayAddress = new io.ipfs.multiaddr.MultiAddress(ipfsConfigParams.gatewayAddress);
        Optional<io.ipfs.multiaddr.MultiAddress> proxyTargetAddress = Optional.of(new io.ipfs.multiaddr.MultiAddress(ipfsConfigParams.proxyTarget));

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
                proxyTargetAddress, Optional.empty());

        org.peergos.config.Filter filter = ipfsConfigParams.blockFilter;

        CodecSet codecSet = new CodecSet(Set.of(Cid.Codec.DagCbor, Cid.Codec.Raw));
        DatastoreSection datastoreSection = new DatastoreSection(blockMount, rootMount, filter, codecSet);
        BootstrapSection bootstrapSection = new BootstrapSection(bootstrapNodes);
        // ipfs metrics are merged with peergos metrics, unless running the IPFS standalone command.
        boolean separateIpfsMetrics = ipfsConfigParams.enableMetrics && ipfsConfigParams.metricsPort.isPresent();
        MetricsSection metrics = new MetricsSection(separateIpfsMetrics, ipfsConfigParams.metricsAddress.orElse("localhost"), ipfsConfigParams.metricsPort.orElse(8101));
        Config config = new org.peergos.config.Config(addressesSection, bootstrapSection, datastoreSection,
                identity, metrics);
        return config;
    }
}
