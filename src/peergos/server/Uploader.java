package peergos.server;

import peergos.server.storage.*;
import peergos.shared.*;
import peergos.shared.crypto.random.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

public class Uploader {

    public static void main(String[] args) throws Exception {
        Crypto crypto = Crypto.initJava();
        NetworkAccess network = NetworkAccess.buildJava(new URL("https://demo.peergos.net")).get();
        String username = args[0];
        String fromPath = args[1];
        String toPath = args[2];
        Console console = System.console();
        String password = new String(console.readPassword("Enter password for " + username + ":"));
        UserContext context = UserContext.signIn(username, password, network, crypto).get();
        ForkJoinPool pool = new ForkJoinPool(50);
        long t1 = System.currentTimeMillis();
        uploadTo(context, Paths.get(fromPath), toPath, context.fragmenter(), props -> true, pool);
        long t2 = System.currentTimeMillis();
        System.out.println("Upload took " + (t2-t1) + " mS");
    }

    private static void createPath(FileTreeNode parent, Path path, NetworkAccess network, SafeRandom random) throws Exception {
        String name = path.getName(0).toString();
        Optional<FileTreeNode> child = parent.getChild(name, network).get();
        if (path.getNameCount() == 1) {
            if (! child.isPresent())
                parent.mkdir(name, network, false, random).get();
            return;
        }
        Path subpath = path.subpath(1, path.getNameCount());

        if (! child.isPresent() && path.getNameCount() > 1) {
            parent.mkdir(name, network, false, random).get();
            createPath(child.get(), subpath, network, random);
        } else
            createPath(child.get(), subpath, network, random);
    }

    /**
     *
     * @param target the peergos entry point which can authorise writes
     * @param localSource path to a local file/directory to upload
     * @param targetDir the peergos path to upload to the subtree to
     * @param fragmenter a file fragmenter
     * @param filter the selection of files/directories to upload
     * @param pool thread pool
     * @throws Exception
     */
    public static void uploadTo(UserContext target, Path localSource, String targetDir, Fragmenter fragmenter,
                                Predicate<File> filter, ForkJoinPool pool) throws Exception {
        if (! localSource.toFile().exists())
            throw new IllegalStateException("Local source " + localSource + " doesn't exist!");
        Optional<FileTreeNode> file = target.getByPath(targetDir).get();
        Path targetPath = Paths.get(targetDir);
        Optional<FileTreeNode> fileParent = target.getByPath(targetPath.getParent().toString()).get();
        if (! file.isPresent()) {
            Optional<FileTreeNode> root = target.getByPath("/").get();
            createPath(root.get(), targetPath, target.network, target.crypto.random);
            Optional<FileTreeNode> created = target.getByPath(targetDir).get();
            upload(created, fileParent, localSource, target.network, target.crypto.random, fragmenter, filter, pool);
        } else
            upload(file, fileParent, localSource, target.network, target.crypto.random, fragmenter, filter, pool);
    }

    private static void upload(Optional<FileTreeNode> targetDir,
                               Optional<FileTreeNode> targetParentDir,
                               Path localSource,
                               NetworkAccess network, SafeRandom random,
                               Fragmenter fragmenter,
                               Predicate<File> filter,
                               ForkJoinPool pool) throws Exception {
        pool.submit(() -> targetDir.ifPresent(f -> uploadTo(localSource, f, targetParentDir,
                    network, random, fragmenter, filter))).get();
    }

    public static void uploadTo(Path source, FileTreeNode target, Optional<FileTreeNode> targetParent,
                                NetworkAccess network, SafeRandom random,
                                Fragmenter fragmenter, Predicate<File> filter) {
        File file = source.toFile();
        if (!filter.test(file))
            return;
        Optional<FileTreeNode> existing = await(target.getChild(file.getName(), network));

        if (file.isDirectory()) {
            try {
                if (! existing.isPresent() && ! targetParent.isPresent())
                    throw new IllegalStateException("We cannot create top level directories, they can only be created by signing up");
                if (! existing.isPresent())
                    target.mkdir(file.getName(), network, false, random).get();
                Optional<FileTreeNode> updatedTarget = existing.isPresent() ? Optional.of(target) :
                        targetParent.flatMap(f -> await(f.getChild(target.getName(), network)));

                Optional<FileTreeNode> childDir = updatedTarget.flatMap(f -> await(f.getChild(file.getName(), network)));
                childDir.ifPresent(newDir -> Stream.of(file.list())
                        .parallel()
                        .map(childName -> source.resolve(childName))
                        .forEach(childPath -> uploadTo(childPath, newDir, updatedTarget, network, random, fragmenter, filter)));
            } catch (Exception e) {
                System.err.println("Error uploading children of " + source);
                e.printStackTrace();
            }
        } else {
            try {
                ResetableFileInputStream fileData = new ResetableFileInputStream(file);
                target.uploadFile(file.getName(), fileData, file.length(), network, random, c -> {}, fragmenter).get();
            } catch (Exception e) {
                System.err.println("Error uploading " + source);
                e.printStackTrace();
            }
        }
    }

    private static <T> T await(CompletableFuture<T> source) {
        try {
            return source.get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}