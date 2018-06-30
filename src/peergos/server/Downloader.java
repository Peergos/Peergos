package peergos.server;
import java.util.logging.*;

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

public class Downloader {
	private static final Logger LOG = Logger.getGlobal();

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
        downloadTo(context, fromPath, Paths.get(toPath), props -> true, pool);
        long t2 = System.currentTimeMillis();
        LOG.info("Download took " + (t2-t1) + " mS");
    }

    /**
     *
     * @param source the peergos filesystem view to download from
     * @param origin the root peergos path to download the subtree of
     * @param targetDir the local destination directory
     * @param saveFile filter the files to save
     * @param pool thread pool
     * @throws Exception
     */
    public static void downloadTo(UserContext source, String origin, Path targetDir,
                                  Predicate<FileProperties> saveFile, ForkJoinPool pool) throws Exception {
        if (! targetDir.toFile().exists() && ! targetDir.toFile().mkdirs())
            throw new IllegalStateException("Couldn't create " + targetDir);
        Optional<FileTreeNode> file = source.getByPath(origin).get();
        pool.submit(() -> file.ifPresent(f -> downloadTo(f, targetDir, source.network, source.crypto.random, saveFile))).get();
    }

    public static void downloadTo(FileTreeNode source, Path target, NetworkAccess network, SafeRandom random,
                                  Predicate<FileProperties> saveFile) {
        Path us = target.resolve(source.getName());
        if (source.isDirectory()) {
            try {
                Set<FileTreeNode> children = source.getChildren(network).get();
                if (! us.toFile().exists() && !us.toFile().mkdir())
                    throw new IllegalStateException("Couldn't create directory: " + us);
                children.stream().parallel().forEach(child -> downloadTo(child, us, network, random, saveFile));
            } catch (Exception e) {
                LOG.severe("Error downloading children of " + source.getName());
                LOG.log(Level.WARNING, e.getMessage(), e);
            }
        } else if (saveFile.test(source.getFileProperties())) {
            try (FileOutputStream fout = new FileOutputStream(us.toFile())) {
                long size = source.getSize();
                if (size > Integer.MAX_VALUE)
                    throw new IllegalStateException("Need to implement streaming for files bigger than 2GiB");
                byte[] buf = new byte[(int)size];
                AsyncReader reader = source.getInputStream(network, random, c -> {}).get();
                reader.readIntoArray(buf, 0, buf.length)
                        .get();
                fout.write(buf);
            } catch (Exception e) {
                LOG.severe("Error downloading " + source.getName());
                LOG.log(Level.WARNING, e.getMessage(), e);
            }
        }
    }
}
