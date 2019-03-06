package peergos.server.storage;

import static peergos.server.util.Logging.LOG;

import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.util.*;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

/**
 * A Utility for installing IPFS.
 */
public class IpfsInstaller {

    public enum DownloadTarget {
        DARWIN_386("https://github.com/peergos/ipfs-releases/blob/master/v0.4.19/darwin-386/ipfs?raw=true",
                Cid.decode("QmUXvJed2cfPRiapifvQBz3R7nPEqiXGoz8sx7GYqnU1LQ")),
        DARWIN_AMD64("https://github.com/peergos/ipfs-releases/blob/master/v0.4.19/darwin-amd64/ipfs?raw=true",
                Cid.decode("QmVbPzHTEvj9YEsGfd19qmp8aVxKG1Jsrp4fVdxPYcu7b7")),
        FREEBSD_386("https://github.com/peergos/ipfs-releases/blob/master/v0.4.19/freebsd-386/ipfs?raw=true",
                Cid.decode("QmfRjAyiacie7g7U551acuHp7tBB6JuXyBZ75eWtNEfYqs")),
        FREEBSD_AMD64("https://github.com/peergos/ipfs-releases/blob/master/v0.4.19/freebsd-amd64/ipfs?raw=true",
                Cid.decode("QmctZnHuehmM5fJmmeHERMe5rB4eatq27t5gmQFLRDxQnp")),
        FREEBSD_ARM("https://github.com/peergos/ipfs-releases/blob/master/v0.4.19/freebsd-arm/ipfs?raw=true",
                Cid.decode("QmPu6x7pQHnXVa3uagMDJMzpvY3GWnbFjoxDd7WjPHYyGA")),
        LINUX_386("https://github.com/peergos/ipfs-releases/blob/master/v0.4.19/linux-386/ipfs?raw=true",
                Cid.decode("QmW7oUFHk21MQWKTHA9cg5XP1uL564BEaajTEWtQj1vS4X")),
        LINUX_AMD64("https://github.com/peergos/ipfs-releases/blob/master/v0.4.19/linux-amd64/ipfs?raw=true",
                Cid.decode("Qmdpq7PfNA4HuFdURnFd55DBU1DSFqBAaEFKfXbECRnRQW")),
        LINUX_ARM("https://github.com/peergos/ipfs-releases/blob/master/v0.4.19/linux-arm/ipfs?raw=true",
                Cid.decode("QmfYciSxAwcEzvb7VoWsdfM6odjWXkeocNUbdPtDLnf9Ze")),
        LINUX_ARM64("https://github.com/peergos/ipfs-releases/blob/master/v0.4.19/linux-arm64/ipfs?raw=true",
                Cid.decode("QmZku7a3AfqKsLSAu2ey3yU4StAuVJzuaWahxCGubVmdYH")),
        WINDOWS_386("https://github.com/peergos/ipfs-releases/blob/master/v0.4.19/windows-386/ipfs.exe?raw=true",
                Cid.decode("QmQXYuKs8wonW4ioTWPyq1u1Gs88MYYdiiKCSNAsinqfEj")),
        WINDOWS_AMD64("https://github.com/peergos/ipfs-releases/blob/master/v0.4.19/windows-amd64/ipfs.exe?raw=true",
                Cid.decode("QmeGbmQHX9ojyDAWWfdtCsXgAQNupgPipu2pVRCgsvHuEs"));

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
        String os = canonicaliseOS(System.getProperty("os.name").toLowerCase());
        String arch = canonicaliseArchitecture(System.getProperty("os.arch"));

        String type = os + "_" + arch;
        return DownloadTarget.valueOf(type.toUpperCase());
    }

    private static String canonicaliseArchitecture(String arch) {
        System.out.println("Looking up architecture: " + arch);
        if (arch.startsWith("arm64"))
            return "arm64";
        if (arch.startsWith("arm"))
            return "arm";
        if (arch.startsWith("x86_64"))
            return "amd64";
        if (arch.startsWith("x86"))
            return "386";
        return arch;
    }

    private static String canonicaliseOS(String os) {
        System.out.println("Looking up OS: " + os);
        if (os.startsWith("mac"))
            return "darwin";
        if (os.startsWith("windows"))
            return "windows";
        return os;
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
//        codegen(Paths.get("/home/ian/ipfs-releases/v0.4.19"));
    }

    private static void codegen(Path root) throws Exception {
        String urlBase = "https://github.com/peergos/ipfs-releases/blob/master/";
        for (File arch: Arrays.asList(root.toFile().listFiles()).stream().sorted().collect(Collectors.toList())) {
            for (File binary: arch.listFiles()) {
                byte[] bytes = Files.readAllBytes(binary.toPath());
                Multihash hash = new Multihash(Multihash.Type.sha2_256, Hash.sha256(bytes));
                System.out.println(arch.getName().toUpperCase().replaceAll("-", "_")
                        + "(\"" + urlBase + arch.getName() + "/" + binary.getName()
                        + "?raw=true\", Cid.decode(\"" + hash + "\")),");
            }
        }
    }

    private static class ReleasePreparation {
        public static void main(String[] a) throws Exception {
            String version = "v0.4.19";
            Path baseDir = Files.createTempDirectory("ipfs");
            for (String os: Arrays.asList("linux", "windows", "darwin", "freebsd")) {
                for (String arch: Arrays.asList("386", "amd64", "arm", "arm64")) {
                    String archive = os.equals("windows") ? "zip" : "tar.gz";
                    String filename = "go-ipfs_"+version+"_" + os + "-" + arch + "." + archive;
                    URI target = new URI("https://dist.ipfs.io/go-ipfs/"+version+"/" + filename);

                    Path archDir = baseDir.resolve(os + "-" + arch);
                    try {
                        archDir.toFile().mkdirs();
                        URLConnection conn = target.toURL().openConnection();
                        Path tarPath = archDir.resolve(filename);
                        System.out.printf("Downloading " + filename + " ...");
                        Files.write(tarPath, Serialize.readFully(conn.getInputStream()));
                        System.out.println("done");
                        ProcessBuilder pb = os.equals("windows") ?
                                new ProcessBuilder("unzip", filename) :
                                new ProcessBuilder("tar", "-xvzf", filename);
                        pb.directory(archDir.toFile());
                        Process proc = pb.start();
                        while (proc.isAlive())
                            Thread.sleep(100);
                        String executable = os.equals("windows") ? "ipfs.exe" : "ipfs";
                        byte[] bytes = Files.readAllBytes(archDir.resolve("go-ipfs").resolve(executable));
                        Multihash computed = new Multihash(Multihash.Type.sha2_256, Hash.sha256(bytes));
                        System.out.println(os + "-" + arch + "-" + computed);
                    } catch (FileNotFoundException e) {
                        archDir.toFile().delete();
                        // OS + arch combination doesn't exist
                    }
                }
            }
        }
    }
}
