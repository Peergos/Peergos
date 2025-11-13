package peergos.server;

import com.webauthn4j.data.client.Origin;
import peergos.server.corenode.IpfsCoreNode;
import peergos.server.corenode.JdbcIpnsAndSocial;
import peergos.server.corenode.UserRepository;
import peergos.server.crypto.hash.ScryptJava;
import peergos.server.login.JdbcAccount;
import peergos.server.space.JdbcUsageStore;
import peergos.server.space.UsageStore;
import peergos.server.sql.SqlSupplier;
import peergos.server.storage.DeletableContentAddressedStorage;
import peergos.server.storage.JdbcServerIdentityStore;
import peergos.server.storage.BlockMetadataStore;
import peergos.server.storage.admin.QuotaAdmin;
import peergos.server.storage.auth.*;
import peergos.shared.Crypto;
import peergos.shared.corenode.CoreNode;
import peergos.shared.corenode.HTTPCoreNode;
import peergos.shared.crypto.hash.PublicKeyHash;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.login.mfa.MultiFactorAuthMethod;
import peergos.shared.mutable.MutablePointers;
import peergos.shared.storage.ContentAddressedStorage;
import peergos.shared.user.CommittedWriterData;
import peergos.shared.user.WriterData;
import peergos.shared.util.ArrayOps;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

public class ServerAdmin {
    public static final Command.Arg ARG_USERNAME =
            new Command.Arg("username", "The username to delete", true);

    private static void deleteOwnedKeys(PublicKeyHash id,
                                        MutablePointers pointers,
                                        JdbcIpnsAndSocial rawPointers,
                                        DeletableContentAddressedStorage storage,
                                        Crypto crypto) {
        Set<PublicKeyHash> done = new HashSet<>();
        deleteOwnedKeysRecurse(id, id, pointers, rawPointers, storage, crypto, done);
    }

    private static void deleteOwnedKeysRecurse(PublicKeyHash id,
                                               PublicKeyHash w,
                                               MutablePointers pointers,
                                               JdbcIpnsAndSocial rawPointers,
                                               DeletableContentAddressedStorage storage,
                                               Crypto crypto,
                                               Set<PublicKeyHash> done) {
        done.add(w);
        Cid ourId = storage.id().join();
        CommittedWriterData.Retriever retriever = (h, s) -> DeletableContentAddressedStorage.getWriterData(
                Collections.emptyList(), id, h, s, false, ourId, crypto.hasher, storage);
        Set<PublicKeyHash> verifiedChildren = DeletableContentAddressedStorage.getDirectOwnedKeys(id, w, pointers,
                retriever, storage, crypto.hasher).join();
        for (PublicKeyHash child : verifiedChildren) {
            if (! done.contains(child))
                deleteOwnedKeysRecurse(id, child, pointers, rawPointers, storage, crypto, done);
        }
        rawPointers.removePointer(w);
    }

    public static final Command<Boolean> MFA_DELETE = new Command<>("delete",
            "Delete a multi-factor auth option for a local user.",
            a -> {
                Builder.disableLog();
                IpfsCoreNode.disableLog();
                ScryptJava.disableLog();
                HTTPCoreNode.disableLog();

                String username = a.getArg("username");
                byte[] credentialID = ArrayOps.hexToBytes(a.getArg("credential-id"));
                SqlSupplier sqlCommands = Builder.getSqlCommands(a);
                Supplier<Connection> dbConnectionPool = Builder.getDBConnector(a, "serverids-file");

                String listeningHost = a.getArg(Main.LISTEN_HOST.name);
                Optional<String> tlsHostname = a.hasArg("tls.keyfile.password") ? Optional.of(listeningHost) : Optional.empty();
                Optional<String> publicHostname = tlsHostname.isPresent() ? tlsHostname : a.getOptionalArg("public-domain");
                int webPort = a.getInt("port");
                Origin origin = new Origin(publicHostname.map(host -> (Main.isLanIP(host) ? "http://" : "https://") + host).orElse("http://localhost:" + webPort));
                String rpId = publicHostname.orElse("localhost");
                JdbcAccount rawAccount = new JdbcAccount(Builder.getDBConnector(a, "account-sql-file", dbConnectionPool), sqlCommands, origin, rpId);
                rawAccount.deleteMfa(username, credentialID);
                System.out.println("Deleted multifactor auth for user " + username + " with credential id " + ArrayOps.bytesToHex(credentialID));

                return true;
            },
            Arrays.asList(
                    ARG_USERNAME,
                    new Command.Arg("log-to-file", "Whether to log to a file", true, "false"),
                    new Command.Arg("print-log-location", "Whether to print log location", true, "false")
            )
    );

    public static final Command<Boolean> MFA = new Command<>("mfa",
            "List multi-factor auth options for a local user.",
            a -> {
                Builder.disableLog();
                IpfsCoreNode.disableLog();
                ScryptJava.disableLog();
                HTTPCoreNode.disableLog();

                String username = a.getArg("username");
                SqlSupplier sqlCommands = Builder.getSqlCommands(a);
                Supplier<Connection> dbConnectionPool = Builder.getDBConnector(a, "serverids-file");

                String listeningHost = a.getArg(Main.LISTEN_HOST.name);
                Optional<String> tlsHostname = a.hasArg("tls.keyfile.password") ? Optional.of(listeningHost) : Optional.empty();
                Optional<String> publicHostname = tlsHostname.isPresent() ? tlsHostname : a.getOptionalArg("public-domain");
                int webPort = a.getInt("port");
                Origin origin = new Origin(publicHostname.map(host -> (Main.isLanIP(host) ? "http://" : "https://") + host).orElse("http://localhost:" + webPort));
                String rpId = publicHostname.orElse("localhost");
                JdbcAccount rawAccount = new JdbcAccount(Builder.getDBConnector(a, "account-sql-file", dbConnectionPool), sqlCommands, origin, rpId);
                List<MultiFactorAuthMethod> mfas = rawAccount.getSecondAuthMethods(username).join();
                System.out.println("Listing multifactor auth options for " + username);
                mfas.forEach(mfa -> {
                    System.out.println(mfa.name + ", credentialID: " + ArrayOps.bytesToHex(mfa.credentialId) + ", enabled: " + mfa.enabled + ", type: " + mfa.type.name());
                });
                return true;
            },
            Arrays.asList(
                    ARG_USERNAME,
                    new Command.Arg("log-to-file", "Whether to log to a file", true, "false"),
                    new Command.Arg("print-log-location", "Whether to print log location", true, "false")
            ),
            Arrays.asList(MFA_DELETE)
    );

    public static final Command<Boolean> DELETE = new Command<>("delete",
            "Delete a user from this server and remove their space quota.",
            a -> {
                try {
                    Builder.disableLog();
                    IpfsCoreNode.disableLog();
                    ScryptJava.disableLog();
                    HTTPCoreNode.disableLog();

                    Crypto crypto = JavaCrypto.init();
                    String username = a.getArg("username");
                    SqlSupplier sqlCommands = Builder.getSqlCommands(a);
                    BlockMetadataStore meta = Builder.buildBlockMetadata(a);
                    Supplier<Connection> dbConnectionPool = Builder.getDBConnector(a, "serverids-file");
                    JdbcServerIdentityStore ids = JdbcServerIdentityStore.build(dbConnectionPool, sqlCommands, crypto);
                    JdbcBatCave batStore = new JdbcBatCave(Builder.getDBConnector(a, "bat-store", dbConnectionPool), sqlCommands);
                    BlockRequestAuthoriser blockAuth = Builder.blockAuthoriser(a, batStore, crypto.hasher);
                    Supplier<Connection> usageDb = Builder.getDBConnector(a, "space-usage-sql-file", dbConnectionPool);
                    JdbcUsageStore usageStore = new JdbcUsageStore(usageDb, sqlCommands);
                    DeletableContentAddressedStorage storage = Builder.buildLocalStorage(a, meta, null, null, blockAuth,
                            ids, usageStore, crypto.hasher);
                    JdbcIpnsAndSocial rawPointers = Builder.buildRawPointers(a,
                            Builder.getDBConnector(a, "mutable-pointers-file", dbConnectionPool));

                    MutablePointers pointers = UserRepository.build(storage, rawPointers, crypto.hasher);
                    QuotaAdmin quota = Builder.buildSpaceQuotas(a, storage,
                            Builder.getDBConnector(a, "space-requests-sql-file", dbConnectionPool),
                            Builder.getDBConnector(a, "quotas-sql-file", dbConnectionPool), false, false);
                    CoreNode core = Builder.buildCorenode(a, storage, null, rawPointers, pointers, null,
                            null, null, quota, null, null, null, null, crypto);
                    core.initialize(false);
                    storage.setPki(core);

                    // set quota to 0
                    quota.removeQuota(username);

                    // get and verify all owned keys, then set them all to empty targets, children first
                    Optional<PublicKeyHash> idOpt = core.getPublicKeyHash(username).join();
                    if (idOpt.isEmpty()) {
                        System.out.println("User " + username + " doesn't exist");
                        return true;
                    }
                    PublicKeyHash id = idOpt.get();
                    deleteOwnedKeys(id, pointers, rawPointers, storage, crypto);
                    // Now delete all usage roots
                    usageStore.removeUser(username);
                    System.out.println("Finished deleting user " + username);
                    return true;
                } catch (SQLException sqe) {
                    throw new RuntimeException(sqe);
                }
            },
            Arrays.asList(
                    ARG_USERNAME,
                    new Command.Arg("log-to-file", "Whether to log to a file", true, "false"),
                    new Command.Arg("print-log-location", "Whether to print log location", true, "false")
            )
    );

    public static final Command<Boolean> STATS = new Command<>("stats",
            "Print statistics for storage",
            a -> {
                Builder.disableLog();
                IpfsCoreNode.disableLog();
                ScryptJava.disableLog();
                HTTPCoreNode.disableLog();

                BlockMetadataStore meta = Builder.buildBlockMetadata(a);

                AtomicLong cborSize = new AtomicLong(0);
                AtomicLong cborCount = new AtomicLong(0);
                AtomicLong rawSize = new AtomicLong(0);
                AtomicLong rawCount = new AtomicLong(0);
                meta.applyToAllSizes((c, s) -> {
                    if (c.isRaw()) {
                        rawCount.incrementAndGet();
                        rawSize.addAndGet(s);
                    } else {
                        cborCount.incrementAndGet();
                        cborSize.addAndGet(s);
                    }
                });

                System.out.println("Cbor blocks: " + cborCount.get() + ", raw blocks: " + rawCount.get() +
                        ", cbor size: " + cborSize.get() + ", raw size: " + rawSize.get());
                return true;
            },
            Arrays.asList(),
            Arrays.asList()
    );

    public static final Command<Boolean> STORAGE = new Command<>("storage",
            "Operations on storage",
            a -> true,
            Arrays.asList(),
            Arrays.asList(STATS)
    );

    public static final Command<Boolean> SERVER_ADMIN = new Command<>("admin",
            "Manage users on this server",
            args -> {
                System.out.println("Run with -help to show options");
                return null;
            },
            Arrays.asList(),
            Arrays.asList(STORAGE, MFA, DELETE)
    );
}
