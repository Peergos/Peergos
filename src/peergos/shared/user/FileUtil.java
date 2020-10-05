package peergos.shared.user;

import peergos.shared.Crypto;
import peergos.shared.NetworkAccess;
import peergos.shared.user.fs.FileWrapper;
import peergos.shared.util.Futures;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class FileUtil {


    public static String getFilename(Path p) {
        return p.getName(p.getNameCount() - 1).toString();
    }

    private static List<String> toList(Path p) {
        return Arrays.asList(toRelative(p).toString().split("/"));
    }

    public static Path canonicalise(Path p) {
        return p.isAbsolute() ? p : Paths.get("/").resolve(p);
    }

    public static Path toRelative(Path p) {
        return p.isAbsolute() ? Paths.get(p.toString().substring(1)) : p;
    }

    public static CompletableFuture<FileWrapper> getOrMkdir(FileWrapper parent, String dirName, Crypto crypto, NetworkAccess network) {
        return parent.getChild(dirName, crypto.hasher, network)
                .thenCompose(opt -> opt.isPresent() ?
                        Futures.of(opt.get()) :
                        parent.mkdir(dirName, network, true, crypto)
                                .thenCompose(p -> p.getChild(dirName, crypto.hasher, network))
                                .thenApply(Optional::get));
    }

    public static CompletableFuture<FileWrapper> getOrMkdirs(FileWrapper parent, Path path, Crypto crypto, NetworkAccess network) {
        return getOrMkdirs(parent, toList(path), crypto, network);
    }

    private static CompletableFuture<FileWrapper> getOrMkdirs(FileWrapper parent, List<String> remaining, Crypto crypto, NetworkAccess network) {
        if (remaining.isEmpty())
            return Futures.of(parent);
        return getOrMkdir(parent, remaining.get(0), crypto, network)
                .thenCompose(child -> getOrMkdirs(child, remaining.subList(1, remaining.size()),crypto, network));
    }
}
