package peergos.server.storage;

import peergos.shared.NetworkAccess;
import peergos.shared.corenode.CoreNode;
import peergos.shared.mutable.MutablePointers;
import peergos.shared.storage.ContentAddressedStorage;

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
                System.out.println("File pinner read usernames "+ usernames);
                // pin their files
                for (String username : usernames) {
                    NetworkAccess.pinAllUserFiles(username, coreNode, mutablePointers, dhtClient);
                    System.out.println("Pinned files for user "+ username);
                }
            } catch (IOException ioe) {
                System.out.println("Failed to read usernames");
                ioe.printStackTrace();
            } catch (InterruptedException | ExecutionException ie) {
                System.out.println("Failed to pin user files");
                ie.printStackTrace();
            }
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
        new Thread(this).start();
    }
}
