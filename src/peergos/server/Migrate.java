package peergos.server;

import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.login.mfa.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.io.*;
import java.net.*;
import java.util.*;

/** Moving a user's account to this server.
 *
 *  The chain building itself is in peergos.shared.user.Migrate, because the browser does it too.
 *  What is here is the part that only a server does: prompting at the console, and taking over a
 *  user whose home server has gone away.
 */
public class Migrate {

    /** Move a user to this server when their home server is unreachable.
     *
     *  Everything the normal migration does with the old server - taking a final snapshot from it, and
     *  having it commit the new chain - is skipped here, so any writes it took since we last mirrored
     *  them are lost. We hold a mirror of their data, so we can read enough of it to log in, and the new
     *  claim only needs their identity key and the pki to be committed.
     */
    public static boolean forceMigrate(Args a) {
        Crypto crypto = Builder.initCrypto();
        String peergosUrl = a.getArg("peergos-url");
        try {
            URL api = new URL(peergosUrl);
            NetworkAccess network = Builder.buildJavaNetworkAccess(api, ! peergosUrl.startsWith("http://localhost"), Optional.of("Peergos-" + UserService.CURRENT_VERSION + "-migrate"), Optional.empty()).join();
            Console console = System.console();
            String username = console.readLine("Enter username to migrate to this server: ");

            List<UserPublicKeyLink> existing = network.coreNode.getChain(username).join();
            if (existing.isEmpty()) {
                System.err.println("Unknown username: " + username);
                return false;
            }
            Multihash currentStorageNodeId = existing.get(existing.size() - 1).claim.storageProviders.stream().findFirst().get();
            Multihash newStorageNodeId = network.dhtClient.id().join();
            if (currentStorageNodeId.equals(newStorageNodeId)) {
                System.err.println("This server is already the home server for " + username + ".");
                return false;
            }

            String password = new String(console.readPassword("Enter password for " + username + ": "));
            SecretSigningKey identity = loginFromMirror(username, password, network, crypto);

            System.out.println("Force migrating user from node " + currentStorageNodeId + " to " + newStorageNodeId);
            List<UserPublicKeyLink> newChain = peergos.shared.user.Migrate
                    .buildMigrationChain(existing, newStorageNodeId, identity).join();
            UserContext.updateChainWithRetry(username, newChain, "", crypto.hasher, network, System.out::println).join();
            List<UserPublicKeyLink> updatedChain = network.coreNode.getChain(username).join();
            if (!updatedChain.get(updatedChain.size() - 1).claim.storageProviders.contains(newStorageNodeId))
                throw new IllegalStateException("Migration failed. Please try again later");
            System.out.println("Migration complete.");
            return true;
        } catch (Exception ex) {
            ex.printStackTrace();
            return false;
        }
    }

    /** The identity key of a user whose home server is unreachable, from our mirror of their data.
     *
     *  A full sign in writes to their filesystem, which their home server would have to accept, so this
     *  only reads: the login algorithm from their WriterData, and their login data from our mirror.
     */
    private static SecretSigningKey loginFromMirror(String username,
                                                    String password,
                                                    NetworkAccess network,
                                                    Crypto crypto) {
        WriterData userData = WriterData.fromCbor(UserContext.getWriterDataCbor(network, username).join().right);
        SecretGenerationAlgorithm algorithm = userData.generationAlgorithm
                .orElseThrow(() -> new IllegalStateException("No login algorithm specified in user data!"));
        UserWithRoot credentials = UserUtil.generateUser(username, password, crypto, algorithm).join();
        SigningKeyPair loginKeys = credentials.getUser();
        byte[] auth = TimeLimitedClient.signNow(loginKeys.secretSigningKey).join();
        Either<UserStaticData, MultiFactorAuthRequest> login = network.account.getLoginData(username,
                loginKeys.publicSigningKey, auth, Optional.empty(), false, false, true).join();
        if (login.isB())
            throw new IllegalStateException("Second factor auth is never mirrored, so " + username +
                    " can only be migrated by their home server");
        return login.a().getData(credentials.getRoot()).identity
                .orElseThrow(() -> new IllegalStateException("No identity key in login data!"))
                .secretSigningKey;
    }
}
