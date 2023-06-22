package peergos.server;

import peergos.shared.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

public class Downloader {

    public static void main(String[] args) throws Exception {
        Crypto crypto = Main.initCrypto();
        NetworkAccess network = Builder.buildJavaNetworkAccess(new URL("https://peergos.net"), true).get();
        String username = args[0];
        String fromPath = args[1];
        String toPath = args[2];
        Console console = System.console();
        String password = new String(console.readPassword("Enter password for " + username + ":"));
        UserContext context = UserContext.signIn(username, password, Main::getMfaResponseCLI, network, crypto).get();
        ForkJoinPool pool = new ForkJoinPool(50);
        long t1 = System.currentTimeMillis();
        downloadTo(context, fromPath, PathUtil.get(toPath), props -> true, pool);
        long t2 = System.currentTimeMillis();
        System.out.println("Download took " + (t2-t1) + " mS");
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
        Optional<FileWrapper> file = source.getByPath(origin).get();
        pool.submit(() -> file.ifPresent(f -> downloadTo(f, targetDir, source.network, source.crypto, saveFile))).get();
    }

    public static void downloadTo(FileWrapper source, Path target, NetworkAccess network, Crypto crypto,
                                  Predicate<FileProperties> saveFile) {
        Path us = target.resolve(source.getName());
        if (source.isDirectory()) {
            try {
                Set<FileWrapper> children = source.getChildren(crypto.hasher, network).get();
                if (! us.toFile().exists() && !us.toFile().mkdir())
                    throw new IllegalStateException("Couldn't create directory: " + us);
                children.stream().parallel().forEach(child -> downloadTo(child, us, network, crypto, saveFile));
            } catch (Exception e) {
                System.err.println("Error downloading children of " + source.getName());
                e.printStackTrace();
            }
        } else if (saveFile.test(source.getFileProperties())) {
            try (FileOutputStream fout = new FileOutputStream(us.toFile())) {
                long size = source.getSize();
                if (size > Integer.MAX_VALUE)
                    throw new IllegalStateException("Need to implement streaming for files bigger than 2GiB");
                byte[] buf = new byte[(int)size];
                AsyncReader reader = source.getInputStream(network, crypto, c -> {}).get();
                reader.readIntoArray(buf, 0, buf.length)
                        .get();
                fout.write(buf);
            } catch (Exception e) {
                System.err.println("Error downloading " + source.getName());
                e.printStackTrace();
            }
        }
    }
}
