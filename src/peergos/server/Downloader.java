package peergos.server;

import peergos.shared.*;
import peergos.shared.crypto.random.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;

public class Downloader {

    public static void main(String[] args) throws Exception {
        Crypto crypto = Crypto.initJava();
        NetworkAccess network = NetworkAccess.buildJava(new URL("https://demo.peergos.net")).get();
        String username = args[0];
        String password = args[1];
        String fromPath = args[2];
        String toPath = args[3];
        UserContext context = UserContext.signIn(username, password, network, crypto).get();
        downloadTo(context, fromPath, Paths.get(toPath));
    }

    public static void downloadTo(UserContext source, String origin, Path targetDir) throws Exception {
        if (! targetDir.toFile().exists() && ! targetDir.toFile().mkdirs())
            throw new IllegalStateException("Couldn't create " + targetDir);
        Optional<FileTreeNode> file = source.getByPath(origin).get();
        file.ifPresent(f -> downloadTo(f, targetDir, source.network, source.crypto.random));
    }

    public static void downloadTo(FileTreeNode source, Path target, NetworkAccess network, SafeRandom random) {
        try {
            Path us = target.resolve(source.getName());
            if (source.isDirectory()) {
                Set<FileTreeNode> children = source.getChildren(network).get();
                if (!us.toFile().mkdir())
                    throw new IllegalStateException("Couldn't create directory: " + us);
                children.forEach(child -> downloadTo(child, us, network, random));
            } else {
                try (FileOutputStream fout = new FileOutputStream(us.toFile());) {
                    AsyncReader reader = source.getInputStream(network, random, count -> {}).get();
                    long size = source.getSize();
                    long offset = 0;
                    byte[] buf = new byte[5 * 1024 * 1024];
                    while (offset < size) {
                        int count = Math.min(buf.length, (int) (size - offset));
                        reader.readIntoArray(buf, 0, count).get();
                        fout.write(buf, 0, count);
                        offset += count;
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
