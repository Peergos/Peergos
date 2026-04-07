package peergos.server.storage;

import com.webauthn4j.data.client.Origin;
import peergos.server.Builder;
import peergos.server.Main;
import peergos.server.corenode.JdbcIpnsAndSocial;
import peergos.server.corenode.UserRepository;
import peergos.server.login.AccountWithStorage;
import peergos.server.login.JdbcAccount;
import peergos.server.space.JdbcUsageStore;
import peergos.server.space.UsageStore;
import peergos.server.sql.PostgresCommands;
import peergos.server.sql.SqlSupplier;
import peergos.server.sql.SqliteCommands;
import peergos.server.storage.admin.QuotaAdmin;
import peergos.server.storage.auth.BlockRequestAuthoriser;
import peergos.server.storage.auth.JdbcBatCave;
import peergos.server.util.Args;
import peergos.server.util.JavaPoster;
import peergos.server.util.Logging;
import peergos.shared.Crypto;
import peergos.shared.corenode.CoreNode;
import peergos.shared.crypto.hash.Hasher;
import peergos.shared.crypto.hash.PublicKeyHash;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.mutable.HttpMutablePointers;
import peergos.shared.mutable.MutablePointers;
import peergos.shared.mutable.MutablePointersProxy;
import peergos.shared.storage.BlockStoreProperties;
import peergos.shared.storage.ContentAddressedStorageProxy;
import peergos.shared.storage.RamBlockCache;
import peergos.shared.user.Account;
import peergos.shared.util.Futures;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class S3HasBlock {
    public static void main(String[] args) throws IOException {
        Args a = Args.parse(args);
        Logging.init(a.with("log-to-console", "true"));
        Crypto crypto = Main.initCrypto();
        Hasher hasher = crypto.hasher;
        S3Config config = S3Config.build(a, Optional.empty());
        boolean versioned = a.getBoolean("s3.versioned-bucket");
        boolean usePostgres = a.getBoolean("use-postgres", false);
        SqlSupplier sqlCommands = usePostgres ?
                new PostgresCommands() :
                new SqliteCommands();
        Supplier<Connection> database = Main.getDBConnector(a, "mutable-pointers-file");
        Supplier<Connection> transactionsDb = Main.getDBConnector(a, "transactions-sql-file");
        TransactionStore transactions = JdbcTransactionStore.build(transactionsDb, sqlCommands);
        BlockRequestAuthoriser authoriser = (c, b, s, auth) -> Futures.of(true);
        BlockMetadataStore meta = Builder.buildBlockMetadata(a);
        Supplier<Connection> usageDb = Main.getDBConnector(a, "space-usage-sql-file");
        UsageStore usage = new JdbcUsageStore(usageDb, sqlCommands);
        Supplier<Connection> statusDb = Main.getDBConnector(a, "partition-status-file");
        PartitionStatus partitioned = new JdbcPartitionStatus(statusDb, sqlCommands);
        JavaPoster p2pHttpProxy = Builder.buildP2pHttpProxy(a);
        ContentAddressedStorageProxy p2pHttpFallback = new ContentAddressedStorageProxy.HTTP(p2pHttpProxy);
        p2pHttpFallback = Builder.buildP2PBlockRetrieverForS3(a, usage, hasher, p2pHttpFallback);
        List<Cid> ids = List.of(Cid.decode(a.getArg("ipfs.id")));
        S3BlockStorage s3 = new S3BlockStorage(config, ids,
                BlockStoreProperties.empty(), "localhost:8000", transactions, authoriser, null, meta, usage,
                new RamBlockCache(1024, 100),
                new FileBlockBuffer(a.fromPeergosDir("s3-block-buffer-dir", "block-buffer"), usage),
                Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
                versioned, a.getPeergosDir(), partitioned, hasher,
                new RAMStorage(hasher), p2pHttpFallback);
        JdbcIpnsAndSocial rawPointers = new JdbcIpnsAndSocial(database, sqlCommands);

        MutablePointers localPointers = UserRepository.build(s3, rawPointers, hasher);
        Multihash pkiServerNodeId = Builder.getPkiServerId(a);
        MutablePointersProxy proxingMutable = new HttpMutablePointers(p2pHttpProxy, pkiServerNodeId);
        LinkRetrievalCounter linkCounts = new JdbcLinkRetrievalcounter(Main.getDBConnector(a, "link-counts-sql-file", database), sqlCommands);
        JdbcIpnsAndSocial rawSocial = new JdbcIpnsAndSocial(Builder.getDBConnector(a, "social-sql-file", database), sqlCommands);
        String listeningHost = a.getArg(Main.LISTEN_HOST.name, "localhost");
        int webPort = a.getInt("port");
        Optional<String> tlsHostname = a.hasArg("tls.keyfile.password") ? Optional.of(listeningHost) : Optional.empty();
        Optional<String> publicHostname = tlsHostname.isPresent() ? tlsHostname : a.getOptionalArg("public-domain");
        Origin origin = new Origin(publicHostname.map(host -> (Main.isLanIP(host) ? "http://" : "https://") + host).orElse("http://localhost:" + webPort));
        String rpId = publicHostname.orElse("localhost");
        JdbcAccount rawAccount = new JdbcAccount(Builder.getDBConnector(a, "account-sql-file", database), sqlCommands, origin, rpId);
        Account account = new AccountWithStorage(s3, localPointers, rawAccount);
        boolean isPki = false;
        InetSocketAddress userAPIAddress = new InetSocketAddress(listeningHost, webPort);
        boolean localhostApi = userAPIAddress.getHostName().equals("localhost");
        QuotaAdmin userQuotas = Main.buildSpaceQuotas(a, s3,
                Main.getDBConnector(a, "space-requests-sql-file", database),
                Main.getDBConnector(a, "quotas-sql-file", database), isPki, localhostApi);
        JdbcBatCave batStore = new JdbcBatCave(Main.getDBConnector(a, "bat-store", database), sqlCommands);

        CoreNode core = Builder.buildCorenode(a, s3, transactions, rawPointers, localPointers, proxingMutable,
                rawSocial, usage, userQuotas, rawAccount, batStore, account, linkCounts, crypto);

        s3.setPki(core);
        PublicKeyHash owner = PublicKeyHash.fromString(a.getArg("owner"));
        Cid hash = Cid.decode(a.getArg("hash"));

        Optional<BlockMetadata> blockMetadata = meta.get(hash);
        System.out.println("Block present in metadb " + owner + ": " + hash + " " + blockMetadata.isPresent());
        System.out.println("Stored owner " + meta.getOwner(hash));
        System.out.println("Block present " + owner + ": " + hash + " " + s3.hasBlock(owner, hash));
        List<Multihash> peerIds = ids.stream()
                .map(c -> (Multihash) c)
                .collect(Collectors.toList());
        Optional<byte[]> block = s3.getRaw(peerIds, null, hash, Optional.empty(), ids.get(0), hasher, false, false).join();
        Files.write(Paths.get(hash + ".data"), block.get());
    }
}
