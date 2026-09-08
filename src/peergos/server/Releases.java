package peergos.server;

import peergos.server.net.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

/** Download Peergos releases from Peergos itself, using the public files under /peergos/releases
 */
public class Releases {

    private static final String RELEASES_PATH = "peergos/releases";

    private static final Map<String, Pair<String, String>> TYPES = new LinkedHashMap<>();
    static {
        TYPES.put("jar", new Pair<>("java", ".jar"));
        TYPES.put("flatpak", new Pair<>("linux", ".flatpak"));
        TYPES.put("rpm", new Pair<>("linux", ".rpm"));
        TYPES.put("msi", new Pair<>("windows", ".msi"));
        TYPES.put("pkg", new Pair<>("macos", ".pkg"));
        TYPES.put("apk", new Pair<>("android", ".apk"));
    }

    public static final Command<Version> LATEST_VERSION = new Command<>("latest-version",
            "Print the latest released version of Peergos",
            a -> {
                Crypto crypto = Main.initCrypto();
                NetworkAccess network = buildNetwork(a);
                FileWrapper releases = getReleasesDir(network, crypto);
                Version latest = latestVersion(releases, network, crypto);
                System.out.println(latest);
                return latest;
            },
            Arrays.asList(
                    new Command.Arg("peergos-url", "Address of the Peergos server to download from", false, "https://peergos.net"),
                    Main.ARG_HTTP_PROXY
            )
    );

    public static final Command<Boolean> GET = new Command<>("get",
            "Download a Peergos release",
            a -> {
                Crypto crypto = Main.initCrypto();
                String type = a.getArg("type").toLowerCase();
                Pair<String, String> platform = TYPES.get(type);
                if (platform == null)
                    throw new IllegalStateException("Unknown release type: " + type + ", must be one of " + TYPES.keySet());

                NetworkAccess network = buildNetwork(a);
                FileWrapper releases = getReleasesDir(network, crypto);
                Version version = a.getOptionalArg("version")
                        .map(Releases::parseVersion)
                        .orElseGet(() -> latestVersion(releases, network, crypto));

                FileWrapper platformDir = getPlatformDir(releases, version, platform.left, network, crypto);
                FileWrapper release = selectRelease(platformDir, platform.right, a.getArg("arch"), network, crypto);

                Path dir = PathUtil.get(a.getArg("dir")).toAbsolutePath();
                if (! dir.toFile().isDirectory())
                    throw new IllegalStateException("Not a directory: " + dir);
                Path target = dir.resolve(release.getName());
                if (target.toFile().exists() && ! a.getBoolean("overwrite"))
                    throw new IllegalStateException(target + " already exists. Supply -overwrite true to replace it.");

                System.out.println("Downloading Peergos " + version + " (" + type + ") to " + target);
                try {
                    download(release, target, network, crypto);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                System.out.println("Saved " + target);
                return true;
            },
            Arrays.asList(
                    new Command.Arg("type", "The kind of release to download, one of " + TYPES.keySet(), false, "jar"),
                    new Command.Arg("version", "The release to download, e.g. 1.33.0 (defaults to the latest)", false),
                    new Command.Arg("arch", "The cpu architecture to download for", false, System.getProperty("os.arch")),
                    new Command.Arg("dir", "The local directory to download into", false, "."),
                    new Command.Arg("overwrite", "Whether to overwrite an existing file", false, "false"),
                    new Command.Arg("peergos-url", "Address of the Peergos server to download from", false, "https://peergos.net"),
                    Main.ARG_HTTP_PROXY
            )
    );

    public static final Command<Boolean> RELEASE = new Command<>("release",
            "Commands for downloading Peergos releases",
            args -> {
                System.out.println("Run with -help to show options");
                return null;
            },
            Arrays.asList(
                    new Command.Arg("print-log-location", "Whether to print the log file location at startup", false, "false"),
                    new Command.Arg("log-to-file", "Whether to log to a file", false, "false"),
                    new Command.Arg("log-to-console", "Whether to log to the console", false, "false")
            ),
            Arrays.asList(LATEST_VERSION, GET)
    );

    private static NetworkAccess buildNetwork(Args a) {
        try {
            String peergosUrl = a.getArg("peergos-url");
            Optional<ProxySelector> proxy = ProxyChooser.build(a);
            return Builder.buildJavaNetworkAccess(new URL(peergosUrl), peergosUrl.startsWith("https"),
                    Optional.of("Peergos-" + UserService.CURRENT_VERSION + "-release"), proxy).join();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static FileWrapper getReleasesDir(NetworkAccess network, Crypto crypto) {
        return UserContext.getPublicFile(PathUtil.get(RELEASES_PATH), network, crypto).join()
                .orElseThrow(() -> new IllegalStateException("Couldn't retrieve /" + RELEASES_PATH));
    }

    private static Version parseVersion(String version) {
        return Version.parse(version.startsWith("v") ? version.substring(1) : version);
    }

    private static Optional<Version> parseDirName(String name) {
        try {
            return name.startsWith("v") ? Optional.of(Version.parse(name.substring(1))) : Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    public static Version latestVersion(FileWrapper releases, NetworkAccess network, Crypto crypto) {
        return releases.getChildren(crypto.hasher, network).join().stream()
                .filter(FileWrapper::isDirectory)
                .flatMap(f -> parseDirName(f.getName()).stream())
                .max(Comparator.naturalOrder())
                .orElseThrow(() -> new IllegalStateException("No releases found in /" + RELEASES_PATH));
    }

    private static FileWrapper getPlatformDir(FileWrapper releases,
                                              Version version,
                                              String platform,
                                              NetworkAccess network,
                                              Crypto crypto) {
        return releases.getDescendentByPath("v" + version + "/" + platform, crypto.hasher, network).join()
                .orElseThrow(() -> new IllegalStateException("No " + platform + " release for version " + version));
    }

    private static List<String> archAliases(String arch) {
        switch (arch.toLowerCase()) {
            case "amd64":
            case "x86_64":
            case "x64":
                return Arrays.asList("amd64", "x86_64", "x64");
            case "aarch64":
            case "arm64":
                return Arrays.asList("aarch64", "arm64");
            default:
                return Arrays.asList(arch.toLowerCase());
        }
    }

    private static FileWrapper selectRelease(FileWrapper platformDir,
                                             String extension,
                                             String arch,
                                             NetworkAccess network,
                                             Crypto crypto) {
        List<FileWrapper> matching = platformDir.getChildren(crypto.hasher, network).join().stream()
                .filter(f -> ! f.isDirectory())
                .filter(f -> f.getName().toLowerCase().endsWith(extension))
                .sorted(Comparator.comparing(FileWrapper::getName))
                .collect(Collectors.toList());
        if (matching.isEmpty())
            throw new IllegalStateException("No " + extension + " release in " + platformDir.getName());
        if (matching.size() == 1)
            return matching.get(0);

        List<String> aliases = archAliases(arch);
        List<FileWrapper> forArch = matching.stream()
                .filter(f -> aliases.stream().anyMatch(alias -> f.getName().toLowerCase().contains(alias)))
                .collect(Collectors.toList());
        if (forArch.size() != 1)
            throw new IllegalStateException("Couldn't pick a release for architecture " + arch + " from " +
                    matching.stream().map(FileWrapper::getName).collect(Collectors.toList()) + ", supply -arch");
        return forArch.get(0);
    }

    private static void download(FileWrapper release,
                                 Path target,
                                 NetworkAccess network,
                                 Crypto crypto) throws IOException {
        long size = release.getSize();
        Path partial = target.resolveSibling(target.getFileName() + ".part");
        byte[] buf = new byte[1024 * 1024];
        try (AsyncReader reader = release.getInputStream(network, crypto, size, 10, x -> {}).join();
             OutputStream fout = Files.newOutputStream(partial)) {
            long done = 0;
            long lastReported = 0;
            while (done < size) {
                int read = reader.readIntoArray(buf, 0, (int) Math.min(buf.length, size - done)).join();
                if (read <= 0)
                    throw new IllegalStateException("Download truncated after " + done + " of " + size + " bytes");
                fout.write(buf, 0, read);
                done += read;
                if (done == size || done - lastReported >= 10 * 1024 * 1024) {
                    lastReported = done;
                    System.out.print("\rDownloaded " + done * 100 / size + "%");
                }
            }
        }
        System.out.println();
        Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
    }
}
