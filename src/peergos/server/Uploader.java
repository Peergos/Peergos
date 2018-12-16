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
        ForkJoinPool pool = new ForkJoinPool(1);
        long t1 = System.currentTimeMillis();
        try {
            uploadTo(context, Paths.get(fromPath), Paths.get(toPath), props -> true, pool);
        } finally {
            pool.shutdown();
        }
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
     * @param context The target user to upload the files to
     * @param localSource path to a local file/directory to upload
     * @param targetDir the peergos path to upload to the subtree to
     * @param filter the selection of files/directories to upload
     * @param pool thread pool
     * @throws Exception
     */
    public static void uploadTo(UserContext context,
                                Path localSource,
                                Path targetDir,
                                Predicate<File> filter,
                                ForkJoinPool pool) throws Exception {
        if (! localSource.toFile().exists())
            throw new IllegalStateException("Local source " + localSource + " doesn't exist!");
        Optional<FileTreeNode> file = context.getByPath(targetDir.toString()).get();
        if (! file.isPresent()) {
            Optional<FileTreeNode> root = context.getByPath("/").get();
            createPath(root.get(), targetDir, context.network, context.crypto.random);
            pool.submit(() -> uploadTo(context, localSource, targetDir, filter)).get();
        } else
            pool.submit(() -> uploadTo(context, localSource, targetDir, filter)).get();
    }

    public static void uploadTo(UserContext context,
                                Path source,
                                Path targetParent,
                                Predicate<File> filter) {
        File file = source.toFile();
        if (!filter.test(file))
            return;
        Optional<FileTreeNode> existing = await(context.getByPath(targetParent.resolve(file.getName()).toString()));

        System.out.println("Uploading " + file);
        if (file.isDirectory()) {
            try {
                if (! existing.isPresent())
                    await(context.getByPath(targetParent.toString())).get()
                            .mkdir(file.getName(), context.network, false, context.crypto.random).get();

                Optional<FileTreeNode> childDir = await(context.getByPath(targetParent.resolve(source.getFileName()).toString()));
                childDir.ifPresent(newDir -> Optional.ofNullable(file.list())
                        .map(Stream::of)
                        .orElse(Stream.empty())
                        .parallel()
                        .map(source::resolve)
                        .forEach(childPath -> uploadTo(context, childPath, targetParent.resolve(file.getName()), filter)));
            } catch (Exception e) {
                System.err.println("Error uploading children of " + source);
                e.printStackTrace();
            }
        } else {
            try {
                ResetableFileInputStream fileData = new ResetableFileInputStream(file);
                await(context.getByPath(targetParent.toString())).get()
                        .uploadFile(file.getName(), fileData, file.length(),
                                context.network, context.crypto.random, c -> {}, context.fragmenter).get();
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