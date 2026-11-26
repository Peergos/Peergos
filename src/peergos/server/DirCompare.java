package peergos.server;

import peergos.server.crypto.hash.ScryptJava;
import peergos.server.sync.PeergosSyncFS;
import peergos.shared.Crypto;
import peergos.shared.NetworkAccess;
import peergos.shared.storage.ContentAddressedStorage;
import peergos.shared.user.UserContext;
import peergos.shared.user.fs.FileProperties;
import peergos.shared.user.fs.FileWrapper;
import peergos.shared.user.fs.NamedAbsoluteCapability;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Compares a local directory with a Peergos directory by Merkle tree hash.
 * File hashes are the Peergos chunk hash tree roots, so a local file is hashed with the chunk
 * size of its Peergos counterpart, and only when the sizes already match.
 */
public class DirCompare {

    static final class Node {
        final String name;
        final boolean isDir;
        final long size;
        int chunkSize;
        volatile byte[] fileHash;
        FileWrapper unhashed;
        final TreeMap<String, Node> children;
        byte[] treeHash;
        long fileCount;

        Node(String name, boolean isDir, long size) {
            this.name = name;
            this.isDir = isDir;
            this.size = size;
            this.children = isDir ? new TreeMap<>() : null;
        }
    }

    static Node child(Node parent, String name, boolean isDir, long size) {
        synchronized (parent) {
            Node existing = parent.children.get(name);
            if (existing != null)
                return existing;
            Node n = new Node(name, isDir, size);
            parent.children.put(name, n);
            return n;
        }
    }

    static Node insert(Node root, String relPath, boolean isDir, long size) {
        String[] parts = relPath.split("/");
        Node current = root;
        for (int i = 0; i < parts.length - 1; i++)
            current = child(current, parts[i], true, 0);
        return child(current, parts[parts.length - 1], isDir, size);
    }

    static Node find(Node root, String relPath) {
        Node current = root;
        for (String part : relPath.split("/")) {
            if (current == null || !current.isDir)
                return null;
            current = current.children.get(part);
        }
        return current;
    }

    static byte[] computeTreeHashes(Node n) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        if (!n.isDir) {
            n.fileCount = 1;
            if (n.fileHash != null)
                n.treeHash = n.fileHash;
            else
                n.treeHash = md.digest(("unhashed:" + n.size).getBytes(StandardCharsets.UTF_8));
            return n.treeHash;
        }
        long count = 0;
        for (Node c : n.children.values()) {
            byte[] h = computeTreeHashes(c);
            count += c.fileCount;
            md.update(c.name.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update((byte) (c.isDir ? 'd' : 'f'));
            md.update(h);
        }
        n.fileCount = count;
        n.treeHash = md.digest();
        return n.treeHash;
    }

    static long diff(String path, Node local, Node remote, PrintStream out) {
        if (Arrays.equals(local.treeHash, remote.treeHash))
            return 0;
        if (local.isDir != remote.isDir) {
            out.println("TYPE      " + path + " (local " + (local.isDir ? "dir" : "file") + ", peergos " + (remote.isDir ? "dir" : "file") + ")");
            return 1;
        }
        if (!local.isDir) {
            if (local.size != remote.size)
                out.println("SIZE      " + path + " (local " + local.size + ", peergos " + remote.size + ")");
            else
                out.println("CONTENT   " + path);
            return 1;
        }
        long diffs = 0;
        for (Map.Entry<String, Node> e : local.children.entrySet()) {
            String childPath = path.isEmpty() ? e.getKey() : path + "/" + e.getKey();
            Node r = remote.children.get(e.getKey());
            if (r == null) {
                out.println("LOCAL     " + childPath + describe(e.getValue()));
                diffs++;
            } else
                diffs += diff(childPath, e.getValue(), r, out);
        }
        for (Map.Entry<String, Node> e : remote.children.entrySet()) {
            if (!local.children.containsKey(e.getKey())) {
                String childPath = path.isEmpty() ? e.getKey() : path + "/" + e.getKey();
                out.println("PEERGOS   " + childPath + describe(e.getValue()));
                diffs++;
            }
        }
        return diffs;
    }

    static String describe(Node n) {
        return n.isDir ? "/ (dir, " + n.fileCount + " files)" : "";
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b)
            sb.append(String.format("%02x", x));
        return sb.toString();
    }

    static void walkLocal(Path root, Path dir, Node tree, AtomicLong count) throws IOException {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path c : ds) {
                BasicFileAttributes attrs;
                try {
                    attrs = Files.readAttributes(c, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                } catch (IOException e) {
                    System.err.println("Couldn't read attributes of " + c + ": " + e.getMessage());
                    continue;
                }
                if (attrs.isSymbolicLink())
                    continue;
                String rel = root.relativize(c).toString().replace('\\', '/');
                if (attrs.isDirectory()) {
                    insert(tree, rel, true, 0);
                    walkLocal(root, c, tree, count);
                } else if (attrs.isRegularFile()) {
                    insert(tree, rel, false, attrs.size());
                    long n = count.incrementAndGet();
                    if (n % 10_000 == 0)
                        System.err.println("  local: listed " + n + " files");
                }
            }
        }
    }

    static List<Map.Entry<String, Node>> files(Node n, String path, List<Map.Entry<String, Node>> acc) {
        for (Node c : n.children.values()) {
            String p = path.isEmpty() ? c.name : path + "/" + c.name;
            if (c.isDir)
                files(c, p, acc);
            else
                acc.add(Map.entry(p, c));
        }
        return acc;
    }

    static String prompt(Console console, BufferedReader in, String msg) throws IOException {
        if (console != null)
            return console.readLine(msg).trim();
        System.out.print(msg);
        System.out.flush();
        return in.readLine().trim();
    }

    public static void main(String[] a) {
        try {
            run();
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
    }

    static void walkRemote(FileWrapper dir, String rel, Node tree, UserContext ctx, Semaphore fetches,
                           ExecutorService traverser, AtomicLong count) {
        Set<NamedAbsoluteCapability> caps = dir.getChildrenCapabilities(ctx.crypto.hasher, ctx.network).join();
        AtomicLong retrieved = new AtomicLong();
        List<Map.Entry<String, FileWrapper>> subdirs = Collections.synchronizedList(new ArrayList<>());
        Consumer<Set<FileWrapper>> collector = children -> {
            retrieved.addAndGet(children.size());
            for (FileWrapper child : children) {
                String childRel = rel.isEmpty() ? child.getName() : rel + "/" + child.getName();
                FileProperties props = child.getFileProperties();
                if (child.isDirectory()) {
                    insert(tree, childRel, true, 0);
                    subdirs.add(Map.entry(childRel, child));
                    continue;
                }
                Node n = insert(tree, childRel, false, props.size);
                n.chunkSize = props.chunkSize;
                if (props.treeHash.isPresent())
                    n.fileHash = props.treeHash.get().rootHash.hash;
                else
                    n.unhashed = child;
                long c = count.incrementAndGet();
                if (c % 10_000 == 0)
                    System.err.println("  peergos: listed " + c + " files");
            }
        };
        List<NamedAbsoluteCapability> capList = new ArrayList<>(caps);
        List<CompletableFuture<Boolean>> batches = new ArrayList<>();
        for (int i = 0; i < capList.size(); i += ContentAddressedStorage.MAX_CHAMP_GETS) {
            Set<NamedAbsoluteCapability> batch = new HashSet<>(capList.subList(i, Math.min(i + ContentAddressedStorage.MAX_CHAMP_GETS, capList.size())));
            fetches.acquireUninterruptibly();
            batches.add(dir.getChildrenFromCaps(batch, collector, ctx.crypto.hasher, ctx.network)
                    .whenComplete((r, e) -> fetches.release()));
        }
        for (CompletableFuture<Boolean> b : batches)
            b.join();
        if (retrieved.get() != caps.size())
            throw new IllegalStateException("Retrieved " + retrieved.get() + " of " + caps.size() + " children of /" + rel);
        List<Future<?>> subs = new ArrayList<>();
        for (Map.Entry<String, FileWrapper> sub : subdirs)
            subs.add(traverser.submit(() -> walkRemote(sub.getValue(), sub.getKey(), tree, ctx, fetches, traverser, count)));
        for (Future<?> f : subs) {
            try {
                f.get();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    static void run() throws Exception {
        Console console = System.console();
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        String server = prompt(console, in, "Peergos server URL: ");
        String username = prompt(console, in, "Username: ");
        String password = console != null ?
                new String(console.readPassword("Password: ")) :
                prompt(console, in, "Password: ");
        Path localRoot = Paths.get(prompt(console, in, "Local directory: ")).toAbsolutePath().normalize();
        String peergosPath = prompt(console, in, "Peergos directory (e.g. /" + username + "/stuff): ");
        if (!peergosPath.startsWith("/"))
            peergosPath = "/" + peergosPath;
        if (peergosPath.length() > 1 && peergosPath.endsWith("/"))
            peergosPath = peergosPath.substring(0, peergosPath.length() - 1);

        if (!Files.isDirectory(localRoot)) {
            System.err.println("Not a local directory: " + localRoot);
            System.exit(1);
        }


        Crypto crypto = Builder.initCrypto();
        NetworkAccess network = Builder.buildJavaNetworkAccess(URI.create(server).toURL(),
                server.startsWith("https"), Optional.of("Peergos-DirCompare"), Optional.empty()).join();
        UserContext context = UserContext.signIn(username, password, Main::getMfaResponseCLI, network, crypto).join();
        password = null;

        int threads = Math.max(4, Runtime.getRuntime().availableProcessors());
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        long t0 = System.currentTimeMillis();
        Node localTree = new Node("", true, 0);
        Node remoteTree = new Node("", true, 0);

        Future<?> localListing = pool.submit(() -> {
            try {
                walkLocal(localRoot, localRoot, localTree, new AtomicLong());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return null;
        });

        System.err.println("Listing " + peergosPath + " ...");
        FileWrapper remoteRoot = context.getByPath(peergosPath).join()
                .orElseThrow(() -> new IllegalStateException("Peergos directory not found"));
        if (!remoteRoot.isDirectory())
            throw new IllegalStateException("Not a Peergos directory: " + peergosPath);
        PeergosSyncFS remoteFs = new PeergosSyncFS(context, Paths.get(peergosPath));
        AtomicLong remoteCount = new AtomicLong();
        try (ExecutorService traverser = Executors.newVirtualThreadPerTaskExecutor()) {
            walkRemote(remoteRoot, "", remoteTree, context, new Semaphore(20), traverser, remoteCount);
        }
        System.err.println("Peergos: " + remoteCount.get() + " files listed in " + (System.currentTimeMillis() - t0) / 1000 + "s");

        localListing.get();
        List<Map.Entry<String, Node>> localFiles = files(localTree, "", new ArrayList<>());
        System.err.println("Local: " + localFiles.size() + " files listed");

        List<Future<?>> work = new ArrayList<>();
        for (Map.Entry<String, Node> e : files(remoteTree, "", new ArrayList<>())) {
            Node r = e.getValue();
            if (r.unhashed == null)
                continue;
            FileWrapper w = r.unhashed;
            r.unhashed = null;
            work.add(pool.submit(() -> {
                r.fileHash = remoteFs.hashFile(null, Optional.of(w), "", null, r.size, r.chunkSize).rootHash.hash;
                return null;
            }));
        }
        if (!work.isEmpty())
            System.err.println("Hashing " + work.size() + " Peergos files without a stored hash (downloads them)");

        AtomicLong hashed = new AtomicLong();
        AtomicLong toHash = new AtomicLong();
        for (Map.Entry<String, Node> e : localFiles) {
            String rel = e.getKey();
            Node l = e.getValue();
            Node r = find(remoteTree, rel);
            if (r == null || r.isDir || r.size != l.size)
                continue;
            toHash.incrementAndGet();
            Path p = localRoot.resolve(rel);
            int chunkSize = r.chunkSize;
            work.add(pool.submit(() -> {
                l.fileHash = ScryptJava.hashFile(p, crypto.hasher, l.size, chunkSize).rootHash.hash;
                long n = hashed.incrementAndGet();
                if (n % 10_000 == 0)
                    System.err.println("  local: hashed " + n + "/" + toHash.get() + " files");
                return null;
            }));
        }
        for (Future<?> f : work)
            f.get();
        pool.shutdown();

        computeTreeHashes(localTree);
        computeTreeHashes(remoteTree);
        System.err.println("Done in " + (System.currentTimeMillis() - t0) / 1000 + "s");
        System.out.println("Local   tree hash: " + hex(localTree.treeHash) + " (" + localTree.fileCount + " files)");
        System.out.println("Peergos tree hash: " + hex(remoteTree.treeHash) + " (" + remoteTree.fileCount + " files)");
        if (Arrays.equals(localTree.treeHash, remoteTree.treeHash)) {
            System.out.println("IDENTICAL");
            System.exit(0);
        }
        System.out.println("DIFFERENT:");
        long diffs = diff("", localTree, remoteTree, System.out);
        System.out.println(diffs + " differing paths");
        System.exit(2);
    }
}
