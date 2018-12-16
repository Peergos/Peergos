package peergos.server.storage;

import static peergos.server.util.Logging.LOG;

import peergos.server.util.Args;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.util.*;

import java.io.IOException;
import java.net.*;
import java.nio.file.*;

/**
 * A Utility for installing IPFS.
 */
public class IpfsInstaller {

    public enum DownloadTarget {
        LINUX_AMD64 ("https://github.com/cboddy/ipfses/blob/master/linux/ipfs?raw=true",
                new Multihash(Multihash.Type.sha2_256, ArrayOps.hexToBytes("fd7c8d05b806c3e8c129f5938fa1bd39ba0659c7e57c06ec5f86029836570b22")));

        public final String url;
        public final Multihash multihash;

        DownloadTarget(String url, Multihash multihash) {
            this.url = url;
            this.multihash = multihash;
        }
    }


    /**
     * Ensure the ipfs executable is installed and that it's contents are correct.
     */
    public static void ensureInstalled(Path targetFile) {
        ensureInstalled(targetFile, getForPlatform());
    }

    public static void install(Path targetFile) {
        install(targetFile, getForPlatform());
    }

    private static DownloadTarget getForPlatform() {
        String os = System.getProperty("os.name");
        String arch = System.getProperty("os.arch");
        DownloadTarget downloadTarget = null;

        String type = os + "_" + arch;
        switch (type) {
            case "Linux_amd64": {
                return DownloadTarget.LINUX_AMD64;
            }
            default:
                throw new IllegalStateException("Unable to install IPFS for unknown Operating System + cpu architecture: " + type);
        }
    }

    private static void ensureInstalled(Path targetFile, DownloadTarget downloadTarget) {
        if (Files.exists(targetFile)) {
            //check contents are correct
            try {
                byte[] raw = Files.readAllBytes(targetFile);
                Multihash computed = new Multihash(Multihash.Type.sha2_256, Hash.sha256(raw));
                if (computed.equals(downloadTarget.multihash)) {
                    //all present and correct
                    return;
                }
                LOG().info("Existing ipfs-exe " + targetFile + " has a different hash than expected, overwriting");
            } catch (IOException ioe) {
                throw new IllegalStateException(ioe.getMessage(), ioe);
            }
        }
        else {
            LOG().info("ipfs-exe "+ targetFile + " not available");
        }
        install(targetFile, downloadTarget);
    }

    private static void install(Path targetFile, DownloadTarget downloadTarget) {
        try {
            Path cacheFile = getLocalCacheDir().resolve(downloadTarget.multihash.toString());
            if (cacheFile.toFile().exists()) {
                LOG().info("Using  cached IPFS "+  cacheFile);
                byte[] raw = Files.readAllBytes(cacheFile);
                Multihash computed = new Multihash(Multihash.Type.sha2_256, Hash.sha256(raw));
                if (computed.equals(downloadTarget.multihash)) {
                    Files.createSymbolicLink(targetFile, cacheFile);
                    targetFile.toFile().setExecutable(true);
                    return;
                }
            }

            URI uri = new URI(downloadTarget.url);
            LOG().info("Downloading IPFS binary "+ downloadTarget.url +"...");
            byte[] raw = Serialize.readFully(uri.toURL().openStream());
            Multihash computed = new Multihash(Multihash.Type.sha2_256, Hash.sha256(raw));

            if (! computed.equals(downloadTarget.multihash))
                throw new IllegalStateException("Incorrect hash for ipfs binary, aborting install!");

            LOG().info("Writing ipfs-binary to "+ targetFile);
            atomicallySaveToFile(targetFile, raw);
            // save to local cache
            cacheFile.getParent().toFile().mkdirs();
            atomicallySaveToFile(cacheFile, raw);

            targetFile.toFile().setExecutable(true);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void atomicallySaveToFile(Path targetFile, byte[] data) throws IOException {
        Path tempFile = Files.createTempFile("ipfs-temp-exe", "");
        Files.write(tempFile, data);
        try {
            Files.move(tempFile, targetFile, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(tempFile, targetFile);
        }
    }

    private static Path getLocalCacheDir() {
        return Paths.get(System.getProperty("user.home"), ".cache");
    }

    public static void main(String[] args) throws Exception {
        Path ipfsPath = Files.createTempFile("something", ".tmp");
        System.out.println("ipfsPath "+ ipfsPath);
        install(ipfsPath);
    }
    private static class LocalHasher {
        public static void main(String[] a) throws Exception {
            byte[] bytes = Files.readAllBytes(Paths.get("/path/to/local/ipfs"));
            Multihash computed = new Multihash(Multihash.Type.sha2_256, Hash.sha256(bytes));
            System.out.println(computed);
        }
    }
}
