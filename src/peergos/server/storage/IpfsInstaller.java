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
        DARWIN_386("https://github.com/peergos/ipfs-releases/blob/master/v0.4.21/darwin-386/ipfs?raw=true",
                Cid.decode("QmeQhmz7x3zQoKk8YxWctkKJ8eEWFEcBruo4GnvPZfcDZo")),
        DARWIN_AMD64("https://github.com/peergos/ipfs-releases/blob/master/v0.4.21/darwin-amd64/ipfs?raw=true",
                Cid.decode("Qmb7VnuQWsc9Nk1ZPZu9KfGu6kihVQZzKBqzsVENZQqt8B")),
        FREEBSD_386("https://github.com/peergos/ipfs-releases/blob/master/v0.4.21/freebsd-386/ipfs?raw=true",
                Cid.decode("QmSi7mCqhzuyo29kXPCsmyxhACAPwyRpzBwXbpZX4xF5uZ")),
        FREEBSD_AMD64("https://github.com/peergos/ipfs-releases/blob/master/v0.4.21/freebsd-amd64/ipfs?raw=true",
                Cid.decode("QmUN74yCNDnQKycxanzzYN3gwUqtpjvUkG41ZsLMurS4Kk")),
        FREEBSD_ARM("https://github.com/peergos/ipfs-releases/blob/master/v0.4.21/freebsd-arm/ipfs?raw=true",
                Cid.decode("QmTRLF6E8dgHB72EuNFG6UytM2rJzpJFZpT3RdCCasRVLJ")),
        LINUX_386("https://github.com/peergos/ipfs-releases/blob/master/v0.4.21/linux-386/ipfs?raw=true",
                Cid.decode("QmPBV4ghNPBjwNG8EHdmadntDR4mZCqwWN3uAEXAjNzij3")),
        LINUX_AMD64("https://github.com/peergos/ipfs-releases/blob/master/v0.4.21/linux-amd64/ipfs?raw=true",
                Cid.decode("QmY3ZFhh1hqSDcvS5us5JVYWHTrdRU4n3opFxWwx65tWBY")),
        LINUX_ARM("https://github.com/peergos/ipfs-releases/blob/master/v0.4.21/linux-arm/ipfs?raw=true",
                Cid.decode("QmcrXGWcDSBkHbkRfJRVp9NRi7G63BMkZ2uk7pyx6o2qWq")),
        LINUX_ARM64("https://github.com/peergos/ipfs-releases/blob/master/v0.4.21/linux-arm64/ipfs?raw=true",
                Cid.decode("QmXsxwWBUZfsMGMSzUep1zpMbCfzsYGFLkEegGuNL5X192")),
        WINDOWS_386("https://github.com/peergos/ipfs-releases/blob/master/v0.4.21/windows-386/ipfs.exe?raw=true",
                Cid.decode("Qmd62AkrhPzKqg8at8nNMzvTCtNc24eHveQ3m71zrXsVDc")),
        WINDOWS_AMD64("https://github.com/peergos/ipfs-releases/blob/master/v0.4.21/windows-amd64/ipfs.exe?raw=true",
                Cid.decode("QmW6QP2B9JSjikahjZyzq8DEhrLbCB6ZAqmHLP7JvDckcL")),;

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

    public static Path getExecutableForOS(Path targetFile) {
        if (System.getProperty("os.name").toLowerCase().contains("windows"))
            return targetFile.getParent().resolve(targetFile.getFileName() + ".exe");
        return targetFile;
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
                ProcessBuilder pb = new ProcessBuilder(Arrays.asList(targetFile.toString(), "version"));
                Process started = pb.start();
                InputStream in = started.getInputStream();
                String output = new String(Serialize.readFully(in)).trim();
                Version ipfsVersion = Version.parse(output.substring(output.lastIndexOf(" ") + 1));
                LOG().info("Upgrading IPFS from " + ipfsVersion);
                targetFile.toFile().delete();
                install(targetFile, downloadTarget, Optional.of(ipfsVersion));
                return;
            } catch (IOException ioe) {
                throw new IllegalStateException(ioe.getMessage(), ioe);
            }
        }
        else {
            LOG().info("ipfs-exe "+ targetFile + " not available");
        }
        install(targetFile, downloadTarget, Optional.empty());
    }

    private static void install(Path targetFile, DownloadTarget downloadTarget, Optional<Version> previousIpfsVersion) {
        try {
            Path cacheFile = getLocalCacheDir().resolve(downloadTarget.multihash.toString());
            if (cacheFile.toFile().exists()) {
                LOG().info("Using  cached IPFS "+  cacheFile);
                byte[] raw = Files.readAllBytes(cacheFile);
                Multihash computed = new Multihash(Multihash.Type.sha2_256, Hash.sha256(raw));
                if (computed.equals(downloadTarget.multihash)) {
                    try {
                        Files.createSymbolicLink(targetFile, cacheFile);
                    } catch (Throwable t) {
                        // Windows requires extra privilege to symlink, so just copy it
                        Files.copy(cacheFile, targetFile);
                    }
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

            // save to local cache
            cacheFile.getParent().toFile().mkdirs();
            atomicallySaveToFile(cacheFile, raw);

            LOG().info("Writing ipfs-binary to "+ targetFile);
            try {
                atomicallySaveToFile(targetFile, raw);
            } catch (FileAlreadyExistsException e) {
                boolean delete = targetFile.toFile().delete();
                if (! delete)
                    throw new IllegalStateException("Couldn't delete old version of ipfs!");
                atomicallySaveToFile(targetFile, raw);
            }

            targetFile.toFile().setExecutable(true);

            // TODO run any upgrade scripts for IPFS like converting the repo etc.
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
        codegen(Paths.get("/home/ian/ipfs-releases/v0.4.21"));
    }

    private static void codegen(Path root) throws Exception {
        String urlBase = "https://github.com/peergos/ipfs-releases/blob/master/" + root.getFileName() + "/";
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
            String version = "v0.4.21";
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
