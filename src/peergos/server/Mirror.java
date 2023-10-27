package peergos.server;

import peergos.server.corenode.*;
import peergos.server.login.*;
import peergos.server.storage.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.login.mfa.*;
import peergos.shared.mutable.*;
import peergos.shared.storage.*;
import peergos.shared.storage.auth.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.logging.*;

public class Mirror {

    public static final Command<Boolean> INIT = new Command<>("init",
            "Derive the parameters needed to mirror a user's data",
            a -> {
                Crypto crypto = Main.initCrypto();
                String peergosUrl = a.getArg("peergos-url");
                try {
                    URL api = new URL(peergosUrl);
                    NetworkAccess network = Main.buildJavaNetworkAccess(api, !peergosUrl.startsWith("http://localhost")).join();
                    Console console = System.console();
                    String username = a.getArg("username");
                    String password = new String(console.readPassword("Enter password for " + username + ": "));

                    UserContext user = UserContext.signIn(username, password, Main::getMfaResponseCLI, network, crypto).join();
                    Optional<BatWithId> mirrorBat = user.getMirrorBat().join();

                    WriterData userData = WriterData.fromCbor(UserContext.getWriterDataCbor(network, username).join().right);
                    boolean legacyAccount = userData.staticData.isPresent();
                    Optional<SecretGenerationAlgorithm> alg = userData.generationAlgorithm;
                    Optional<SigningKeyPair> loginKeyPair = legacyAccount ?
                            Optional.empty() :
                            Optional.of(UserUtil.generateUser(username, password, crypto.hasher, crypto.symmetricProvider,
                    crypto.random, crypto.signer, crypto.boxer, alg.get()).join().getUser());

                    System.out.println("To mirror all your data on another server run daemon with these additional arguments:");
                    mirrorBat.ifPresent(b -> System.out.println("Set daemon arg -mirror.bat " + b.encode()));
                    loginKeyPair.ifPresent(login -> System.out.println("Set daemon arg -login-keypair " + login));
                    return true;
                } catch (Exception ex) {
                    ex.printStackTrace();
                    return false;
                }
            },
            Arrays.asList(
                    new Command.Arg("username", "The username whose data you want to mirror", true),
                    new Command.Arg("peergos-url", "Address of the Peergos server to connect to", false, "http://localhost:8000")
            )
    );

    public static final Command<Boolean> MIRROR = new Command<>("mirror",
            "Commands related to mirroring your data on another server",
            args -> {
                System.out.println("Run with -help to show options");
                return null;
            },
            Arrays.asList(
                    new Command.Arg("print-log-location", "Whether to print the log file location at startup", false, "false"),
                    new Command.Arg("log-to-file", "Whether to log to a file", false, "false"),
                    new Command.Arg("log-to-console", "Whether to log to the console", false, "false")
            ),
            Arrays.asList(INIT)
    );

    public static void mirrorNode(Multihash nodeId,
                                  BatWithId mirrorBat,
                                  CoreNode core,
                                  MutablePointers p2pPointers,
                                  DeletableContentAddressedStorage storage,
                                  JdbcIpnsAndSocial targetPointers,
                                  TransactionStore transactions,
                                  Hasher hasher) {
        Logging.LOG().log(Level.INFO, "Mirroring data for node " + nodeId);
        List<String> allUsers = core.getUsernames("").join();
        int userCount = 0;
        for (String username : allUsers) {
            List<UserPublicKeyLink> chain = core.getChain(username).join();
            if (chain.get(chain.size() - 1).claim.storageProviders.contains(nodeId)) {
                try {
                    mirrorUser(username, Optional.empty(), Optional.of(mirrorBat), core, p2pPointers, null,
                            storage, targetPointers, null, transactions, hasher);
                    userCount++;
                } catch (Exception e) {
                    Logging.LOG().log(Level.WARNING, "Couldn't mirror user: " + username, e);
                }
            }
        }
        Logging.LOG().log(Level.INFO, "Finished mirroring data for node " + nodeId + ", with " + userCount + " users.");
    }

    /**
     *
     * @param username
     * @param core
     * @param p2pPointers
     * @param storage
     * @param targetPointers
     * @param transactions
     * @param hasher
     * @return The version mirrored
     */
    public static Map<PublicKeyHash, byte[]> mirrorUser(String username,
                                                        Optional<SigningKeyPair> loginAuth,
                                                        Optional<BatWithId> mirrorBat,
                                                        CoreNode core,
                                                        MutablePointers p2pPointers,
                                                        Account p2pAccount,
                                                        DeletableContentAddressedStorage storage,
                                                        JdbcIpnsAndSocial targetPointers,
                                                        JdbcAccount targetAccount,
                                                        TransactionStore transactions,
                                                        Hasher hasher) {
        Logging.LOG().log(Level.INFO, "Mirroring data for " + username + loginAuth.map(k -> " including login data").orElse(" excluding login data"));
        Optional<PublicKeyHash> identity = core.getPublicKeyHash(username).join();
        if (! identity.isPresent())
            return Collections.emptyMap();
        PublicKeyHash owner = identity.get();
        Map<PublicKeyHash, byte[]> versions = new HashMap<>();
        List<Multihash> storageProviders = core.getStorageProviders(owner);
        Set<PublicKeyHash> ownedKeys = DeletableContentAddressedStorage.getOwnedKeysRecursive(owner, owner, p2pPointers,
                (h, s) -> DeletableContentAddressedStorage.getWriterData(storageProviders,  h, s, true, storage), storage, hasher).join();
        for (PublicKeyHash ownedKey : ownedKeys) {
            Optional<byte[]> version = mirrorMutableSubspace(owner, ownedKey, storageProviders, mirrorBat, p2pPointers, storage,
                    targetPointers, transactions, hasher);
            if (version.isPresent())
                versions.put(ownedKey, version.get());
        }
        if (loginAuth.isPresent()) {
            SigningKeyPair login = loginAuth.get();
            Either<UserStaticData, MultiFactorAuthRequest> loginData = p2pAccount.getLoginData(username, login.publicSigningKey,
                    TimeLimitedClient.signNow(login.secretSigningKey), Optional.empty()).join();
            if (loginData.isA()) {
                UserStaticData entryData = loginData.a();
                targetAccount.setLoginData(new LoginData(username, entryData, login.publicSigningKey, Optional.empty())).join();
            } else
                Logging.LOG().log(Level.WARNING, "Unable to mirror login data because 2FA is required");
        }
        Logging.LOG().log(Level.INFO, "Finished mirroring data for " + username);
        return versions;
    }

    /**
     *
     * @param owner
     * @param writer
     * @param p2pPointers
     * @param storage
     * @param targetPointers
     * @return the version mirrored
     */
    public static Optional<byte[]> mirrorMutableSubspace(PublicKeyHash owner,
                                                         PublicKeyHash writer,
                                                         List<Multihash> peerIds,
                                                         Optional<BatWithId> mirrorBat,
                                                         MutablePointers p2pPointers,
                                                         DeletableContentAddressedStorage storage,
                                                         JdbcIpnsAndSocial targetPointers,
                                                         TransactionStore transactions,
                                                         Hasher hasher) {
        Optional<byte[]> updated = p2pPointers.getPointer(owner, writer).join();
        if (! updated.isPresent()) {
            Logging.LOG().log(Level.WARNING, "Skipping unretrievable mutable pointer for: " + writer);
            return updated;
        }

        mirrorMerkleTree(owner, writer, peerIds, updated.get(), mirrorBat, storage, targetPointers, transactions, hasher);
        return updated;
    }

    public static void mirrorMerkleTree(PublicKeyHash owner,
                                        PublicKeyHash writer,
                                        List<Multihash> peerIds,
                                        byte[] newPointer,
                                        Optional<BatWithId> mirrorBat,
                                        DeletableContentAddressedStorage storage,
                                        JdbcIpnsAndSocial targetPointers,
                                        TransactionStore transactions,
                                        Hasher hasher) {
        Optional<byte[]> existing = targetPointers.getPointer(writer).join();
        // First pin the new root, then commit updated pointer
        MaybeMultihash existingTarget = existing.isPresent() ?
                MutablePointers.parsePointerTarget(existing.get(), owner, writer, storage).join().updated :
                MaybeMultihash.empty();
        MaybeMultihash updatedTarget = MutablePointers.parsePointerTarget(newPointer, owner, writer, storage).join().updated;
        // use a mirror call to distinguish from normal pin calls
        TransactionId tid = transactions.startTransaction(owner);
        try {
            storage.mirror(owner, peerIds,
                    existingTarget.toOptional().map(c -> (Cid)c),
                    updatedTarget.toOptional().map(c -> (Cid)c), mirrorBat, storage.id().join(), tid, hasher);
            targetPointers.setPointer(writer, existing, newPointer).join();
        } finally {
            transactions.closeTransaction(owner, tid);
        }
    }
}
