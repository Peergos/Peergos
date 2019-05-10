package peergos.server.storage;
import java.util.logging.*;
import peergos.server.util.Logging;

import peergos.shared.NetworkAccess;
import peergos.shared.corenode.CoreNode;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.mutable.MutablePointers;
import peergos.shared.storage.ContentAddressedStorage;
import peergos.shared.user.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

/**
 * Periodically reads file for username and  pins files for each.
 */
public class UserFilePinner implements Runnable {
	private static final Logger LOG = Logging.LOG();

    private final Path userPath;
    private final CoreNode coreNode;
    private final MutablePointers mutablePointers;
    private final ContentAddressedStorage dhtClient;
    private final int delayMs;
    private volatile boolean isFinished;

    public UserFilePinner(Path userPath, CoreNode coreNode, MutablePointers mutablePointers, ContentAddressedStorage dhtClient, int delayMs) {
        this.userPath = userPath;
        this.coreNode = coreNode;
        this.mutablePointers = mutablePointers;
        this.dhtClient = dhtClient;
        this.delayMs = delayMs;
    }

    public void run() {
        while (! isFinished) {
            try {
                //sleep
                Thread.sleep(delayMs);
                // get usernames
                List<String> usernames = getUsernames();
                LOG.info("File pinner read usernames "+ usernames);
                // pin their files
                for (String username : usernames) {
                    pinAllUserFiles(username, coreNode, mutablePointers, dhtClient);
                    LOG.info("Pinned files for user "+ username);
                }
            } catch (IOException ioe) {
                LOG.info("Failed to read usernames");
                LOG.log(Level.WARNING, ioe.getMessage(), ioe);
            } catch (InterruptedException | ExecutionException ie) {
                LOG.info("Failed to pin user files");
                LOG.log(Level.WARNING, ie.getMessage(), ie);
            }
        }
    }

    public static void pinAllUserFiles(String username, CoreNode coreNode, MutablePointers mutable, ContentAddressedStorage dhtClient) throws ExecutionException, InterruptedException {
        Set<PublicKeyHash> ownedKeysRecursive = WriterData.getOwnedKeysRecursive(username, coreNode, mutable, dhtClient).join();
        Optional<PublicKeyHash> ownerOpt = coreNode.getPublicKeyHash(username).get();
        if (! ownerOpt.isPresent())
            throw new IllegalStateException("Couldn't retrieve public key for " + username);
        PublicKeyHash owner = ownerOpt.get();
        for (PublicKeyHash keyHash: ownedKeysRecursive) {
            Multihash casKeyHash = mutable.getPointerTarget(owner, keyHash, dhtClient).get().get();
            dhtClient.recursivePin(owner, casKeyHash).get();
        }
    }

    public List<String> getUsernames() throws IOException {
        if (! userPath.toFile().exists())
            return Collections.emptyList();
        return Files.readAllLines(userPath)
                .stream()
                .map(String::trim)
                .collect(Collectors.toList());
    }

    public void close() {
        this.isFinished = true;
    }

    public void start() {
        new Thread(this, "File Pinner").start();
    }
}
