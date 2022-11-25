package peergos.server.storage;

import static peergos.server.util.Logging.LOG;

import peergos.server.util.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.api.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.util.*;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

/**
 * A Utility for installing IPFS and associated plugins.
 */
public class IpfsInstaller {

    public enum DownloadTarget {
        DARWIN_AMD64("https://github.com/peergos/ipfs-nucleus-releases/blob/main/v0.2.6/darwin-amd64/ipfs?raw=true",
                Cid.decode("QmeLdZAGpJ5JSZfCnxKsLFSDyFkgdBW6r4Zv3PBdDq13t8")),
        DARWIN_ARM64("https://github.com/peergos/ipfs-nucleus-releases/blob/main/v0.2.6/darwin-arm64/ipfs?raw=true",
                Cid.decode("QmNts2B53EQVmaq6T15McFpLgfbek8kLUjdhBhKoytcTzX")),
        LINUX_AMD64("https://github.com/peergos/ipfs-nucleus-releases/blob/main/v0.2.6/linux-amd64/ipfs?raw=true",
                Cid.decode("QmVrhXzZJVMMobXpfAF3kHpoQ5tBZ917rqe6Znk91UebEv")),
        LINUX_ARM("https://github.com/peergos/ipfs-nucleus-releases/blob/main/v0.2.6/linux-arm/ipfs?raw=true",
                Cid.decode("QmNcMtyzD1jQaeSH696g6xLAv6q9b3BsSQTyt96aK4CzzM")),
        LINUX_ARM64("https://github.com/peergos/ipfs-nucleus-releases/blob/main/v0.2.6/linux-arm64/ipfs?raw=true",
                Cid.decode("Qmd4Gwx1EKd5giES7ANaeSissx8JLdYL6NuqNAAUNA9BzD")),
        WINDOWS_AMD64("https://github.com/peergos/ipfs-nucleus-releases/blob/main/v0.2.6/windows-amd64/ipfs.exe?raw=true",
                Cid.decode("QmUJiEzHwoMCNrF3YwTyHhW1VvbEzFh2GEkq5gqchM9Eif")),;

        public final String url;
        public final Multihash multihash;
        public final List<DownloadTarget> plugins;

        DownloadTarget(String url, Multihash multihash, List<DownloadTarget> plugins) {
            this.url = url;
            this.multihash = multihash;
            this.plugins = Collections.unmodifiableList(plugins);
        }

        DownloadTarget(String url, Multihash multihash) {
            this.url = url;
            this.multihash = multihash;
            this.plugins = Collections.emptyList();
        }
    }

    public interface Plugin {

        void ensureInstalled(Path ipfsDir);

        void configure(IpfsWrapper ipfs);

        final class S3 implements Plugin {
            public static final String TYPE = "S3";
            public final String path, bucket, region, accessKey, secretKey, regionEndpoint;
            public final int workers;

            public S3(S3Config config, int workers) {
                this.path = config.path;
                this.bucket = config.bucket;
                this.region = config.region;
                this.accessKey = config.accessKey;
                this.secretKey = config.secretKey;
                this.regionEndpoint = config.regionEndpoint;
                this.workers = workers;
            }

            public String getFileName() {
                return "go-ds-s3.so";
            }

            public Object toJson() {
                Map<String, Object> res = new TreeMap<>();
                Map<String, Object> child = new TreeMap<>();
                child.put("path", path);
                child.put("bucket", bucket);
                child.put("accessKey", accessKey);
                child.put("secretKey", secretKey);
                child.put("workers", workers);
                child.put("region", region);
                child.put("regionEndpoint", regionEndpoint);
                child.put("type", "s3ds");
                res.put("child", child);
                res.put("mountpoint", "/blocks");
                res.put("prefix", "s3.datastore");
                res.put("type", "measure");
                return res;
            }

            public static S3 build(Args a) {
                S3Config config = S3Config.build(a);
                int workers = Integer.parseInt(a.getArg("ipfs-s3-workers", "5"));
                return new S3(config, workers);
            }

            @Override
            public void configure(IpfsWrapper ipfs) {
                // Do the configuration dance..
                System.out.println("Configuring S3 datastore IPFS plugin");

                // update the config file
                List<Object> mount = Arrays.asList(
                        toJson(),
                        JSONParser.parse("{\n" +
                                "          \"child\": {\n" +
                                "            \"compression\": \"none\",\n" +
                                "            \"path\": \"datastore\",\n" +
                                "            \"type\": \"levelds\"\n" +
                                "          },\n" +
                                "          \"mountpoint\": \"/\",\n" +
                                "          \"prefix\": \"leveldb.datastore\",\n" +
                                "          \"type\": \"measure\"\n" +
                                "        }")
                );
                String mounts = JSONParser.toString(mount);
                ipfs.setConfig("Datastore.Spec.mounts", mounts);

                // replace the datastore spec file
                String newDataStoreSpec = "{\"mounts\":[{\"bucket\":\"" + bucket +
                        "\",\"mountpoint\":\"/blocks\",\"region\":\"" + region +
                        "\",\"rootDirectory\":\"\"},{\"mountpoint\":\"/\",\"path\":\"datastore\",\"type\":\"levelds\"}],\"type\":\"mount\"}";
                Path specPath = ipfs.ipfsDir.resolve("datastore_spec");
                try {
                    Files.write(specPath, newDataStoreSpec.getBytes());
                } catch (IOException e) {
                    throw new RuntimeException("Couldn't overwrite ipfs datastore spec file", e);
                }
            }

            @Override
            public void ensureInstalled(Path ipfsDir) {
            }
        }

        static List<Plugin> parseAll(Args args) {
            List<String> plugins = Arrays.asList(args.getArg("ipfs-plugins", "").split(","))
                    .stream()
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());

            return plugins.stream()
                    .map(name -> parse(name, args))
                    .collect(Collectors.toList());
        }

        static Plugin parse(String pluginName, Args a) {
            switch (pluginName) {
                case "go-ds-s3": {
                    return S3.build(a);
                }
                default:
                    throw new IllegalStateException("Unknown plugin: " + pluginName);
            }
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
        String type = getOsArch();
        return DownloadTarget.valueOf(type.toUpperCase());
    }

    private static String getOsArch() {
        String os = canonicaliseOS(System.getProperty("os.name").toLowerCase());
        String arch = canonicaliseArchitecture(System.getProperty("os.arch"));

        return os + "_" + arch;
    }

    private static String canonicaliseArchitecture(String arch) {
        System.out.println("Looking up architecture: " + arch);
        if (arch.startsWith("arm64") || arch.startsWith("aarch64"))
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

    private static void ensurePluginInstalled(Path targetFile, DownloadTarget downloadTarget) {
        if (Files.exists(targetFile)) {
            //check contents are correct
            try {
                byte[] raw = Files.readAllBytes(targetFile);
                Multihash computed = new Multihash(Multihash.Type.sha2_256, Hash.sha256(raw));
                if (computed.equals(downloadTarget.multihash)) {
                    //all present and correct
                    return;
                }
                targetFile.toFile().delete();
                install(targetFile, downloadTarget, Optional.empty());
                return;
            } catch (IOException ioe) {
                throw new IllegalStateException(ioe.getMessage(), ioe);
            }
        }
        else {
            LOG().info("Binary "+ targetFile + " not available");
        }
        install(targetFile, downloadTarget, Optional.empty());
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
                LOG().info("Upgrading IPFS version");
                targetFile.toFile().delete();
                install(targetFile, downloadTarget, Optional.empty());
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
            Path fileName = targetFile.getFileName();
            File targetParent = targetFile.getParent().toFile();
            if (! targetParent.exists())
                if (! targetParent.mkdirs())
                    throw new IllegalStateException("Couldn't create parent directory: " + targetFile.getParent());
            if (cacheFile.toFile().exists()) {
                LOG().info("Using cached " + fileName + " " + cacheFile);
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
            LOG().info("Downloading " + fileName + " binary "+ downloadTarget.url +"...");
            byte[] raw = Serialize.readFully(uri.toURL().openStream());
            Multihash computed = new Multihash(Multihash.Type.sha2_256, Hash.sha256(raw));

            if (! computed.equals(downloadTarget.multihash))
                throw new IllegalStateException("Incorrect hash for binary, aborting install!");

            // save to local cache
            cacheFile.getParent().toFile().mkdirs();
            atomicallySaveToFile(cacheFile, raw);

            LOG().info("Writing " + fileName + " binary to "+ targetFile);
            try {
                atomicallySaveToFile(targetFile, raw);
            } catch (FileAlreadyExistsException e) {
                boolean delete = targetFile.toFile().delete();
                if (! delete)
                    throw new IllegalStateException("Couldn't delete old version of " + fileName + "!");
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
        String version = "v0.2.6";
        codegen(Paths.get("/home/ian/ipfs-nucleus-releases/" + version));
    }

    private static void codegen(Path root) throws Exception {
        String urlBase = "https://github.com/peergos/ipfs-nucleus-releases/blob/main/" + root.getFileName() + "/";
        for (File arch: Arrays.asList(root.toFile().listFiles()).stream().sorted().collect(Collectors.toList())) {
            for (File binary: arch.listFiles()) {
                if (binary.isDirectory())
                    continue;
                byte[] bytes = Files.readAllBytes(binary.toPath());
                Multihash hash = new Multihash(Multihash.Type.sha2_256, Hash.sha256(bytes));
                System.out.println(arch.getName().toUpperCase().replaceAll("-", "_")
                        + "(\"" + urlBase + arch.getName() + "/" + binary.getName()
                        + "?raw=true\", \nCid.decode(\"" + hash + "\")),");
            }
        }
    }
}
