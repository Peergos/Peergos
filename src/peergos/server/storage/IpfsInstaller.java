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
        S3_LINUX_AMD64("https://github.com/peergos/ipfs-releases/blob/master/v0.4.23/linux-amd64/plugins/s3plugin.so?raw=true",
                Cid.decode("QmaTCMcBbXsMXv9U4Ye53k8JdGJi7SF3JZ21WD2rTLAoAC")),
        DARWIN_386("https://github.com/peergos/ipfs-releases/blob/master/v0.4.23/darwin-386/ipfs?raw=true",
                Cid.decode("QmSM5bhXnbMZXcoHYMvARaEAogw4ZtMfFtuVh542LEJ52h")),
        DARWIN_AMD64("https://github.com/peergos/ipfs-releases/blob/master/v0.4.23/darwin-amd64/ipfs?raw=true",
                Cid.decode("QmcT1RbhR8bjBqkSNLzwXyX2JJnazs5KnNufcS3dbWoLr5")),
        FREEBSD_386("https://github.com/peergos/ipfs-releases/blob/master/v0.4.23/freebsd-386/ipfs?raw=true",
                Cid.decode("QmcKGsLEu4sKHLZ79VGW7n7u1B3Hs1TgPwXGU2ufv2kka4")),
        FREEBSD_AMD64("https://github.com/peergos/ipfs-releases/blob/master/v0.4.23/freebsd-amd64/ipfs?raw=true",
                Cid.decode("QmZJCSPpyUVMgsgwrgFrdeAquF4Wrk7b4zAMeTTHVz952V")),
        FREEBSD_ARM("https://github.com/peergos/ipfs-releases/blob/master/v0.4.23/freebsd-arm/ipfs?raw=true",
                Cid.decode("QmQTNPEbPYDsx7761U1LLD9ad3nfsA3uNTuzQxUkiCBvFe")),
        LINUX_386("https://github.com/peergos/ipfs-releases/blob/master/v0.4.23/linux-386/ipfs?raw=true",
                Cid.decode("QmfXwgKYgP6BdutBE413hUz8FsRqGfNBSTF7jD8eWC11PU")),
        LINUX_AMD64("https://github.com/peergos/ipfs-releases/blob/master/v0.4.23/linux-amd64/ipfs?raw=true",
                Cid.decode("Qmc8WeuSFr5siWiEo81f6Z8oSzvS6GRYBCcMRiedoG3ic9"), Arrays.asList(S3_LINUX_AMD64)),
        LINUX_ARM("https://github.com/peergos/ipfs-releases/blob/master/v0.4.23/linux-arm/ipfs?raw=true",
                Cid.decode("QmRPJWxSTH3iAh69uGcqNWE7wuhznbpVHR2smL5hCKKCmW")),
        LINUX_ARM64("https://github.com/peergos/ipfs-releases/blob/master/v0.4.23/linux-arm64/ipfs?raw=true",
                Cid.decode("QmP3QqtPEUo4J5hrLA1aKSvyxqYhSAannYE6TtnWPvv2c5")),
        WINDOWS_386("https://github.com/peergos/ipfs-releases/blob/master/v0.4.23/windows-386/ipfs.exe?raw=true",
                Cid.decode("QmYjhtFVrD4UHCjCvV8MtNTbbXb1nWRApgGgQGtMkNECAy")),
        WINDOWS_AMD64("https://github.com/peergos/ipfs-releases/blob/master/v0.4.23/windows-amd64/ipfs.exe?raw=true",
                Cid.decode("QmRwVrpGKLpajrEejNoeda34Bc2p1hkxTAtsi1LkySrus4")),;

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
            public final DownloadTarget version;

            public S3(S3Config config, DownloadTarget version) {
                this.path = config.path;
                this.bucket = config.bucket;
                this.region = config.region;
                this.accessKey = config.accessKey;
                this.secretKey = config.secretKey;
                this.regionEndpoint = config.regionEndpoint;
                this.version = version;
            }

            public String getFileName() {
                return "go-ds-s3.so";
            }

            public Object toJson(Multihash nodeId) {
                Map<String, Object> res = new TreeMap<>();
                Map<String, Object> child = new TreeMap<>();
                // Make sure that multiple IPFS instances can use the same S3 bucket by prefixing the path with their nodeID
                String s3PathPrefix = nodeId.toString() + "/" + path;
                child.put("path", s3PathPrefix);
                child.put("bucket", bucket);
                child.put("accessKey", accessKey);
                child.put("secretKey", secretKey);
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

                String osArch = getOsArch();
                DownloadTarget pluginVersion = DownloadTarget.valueOf(TYPE + "_" + osArch.toUpperCase());
                return new S3(config, pluginVersion);
            }

            @Override
            public void configure(IpfsWrapper ipfs) {
                // Do the configuration dance..
                System.out.println("Configuring S3 datastore IPFS plugin");
                Multihash nodeId = ipfs.nodeId();

                // update the config file
                List<Object> mount = Arrays.asList(
                        toJson(nodeId),
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
                IpfsInstaller.ensurePluginInstalled(ipfsDir.resolve("plugins").resolve(getFileName()), version);
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
        String version = "v0.4.23";
        String s3Filename = "s3plugin.so";
        byte[] bytes = Files.readAllBytes(Paths.get("/home", "ian", "ipfs-releases", version,
                "linux-amd64", "plugins", s3Filename));
        Multihash hash = new Multihash(Multihash.Type.sha2_256, Hash.sha256(bytes));
        System.out.println("S3_LINUX_AMD64(\"https://github.com/peergos/ipfs-releases/blob/master/" + version +
                "/linux-amd64/plugins/" + s3Filename + "?raw=true\", Cid.decode(\"" + hash + "\")),");
        codegen(Paths.get("/home/ian/ipfs-releases/" + version));
    }

    private static void codegen(Path root) throws Exception {
        String urlBase = "https://github.com/peergos/ipfs-releases/blob/master/" + root.getFileName() + "/";
        for (File arch: Arrays.asList(root.toFile().listFiles()).stream().sorted().collect(Collectors.toList())) {
            for (File binary: arch.listFiles()) {
                if (binary.isDirectory())
                    continue;
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
            String version = "v0.4.23";
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
