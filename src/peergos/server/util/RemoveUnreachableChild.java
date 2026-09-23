package peergos.server.util;

import peergos.server.*;
import peergos.shared.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.user.fs.cryptree.*;
import peergos.shared.util.*;

import java.io.Console;
import java.net.*;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.*;

/** Detach a child whose writing space has no pointer from its parent directory.
 *
 *  An interrupted revocation can empty a writing space's pointer while the tree above it still
 *  holds a capability naming that writer. Every read that walks into the parent then resolves the
 *  dead writer and fails with "writer not present in snapshot!", which takes out the parent and
 *  everything below it, delete included - so there is no way to clear it from the app.
 *
 *  This removes the parent's link to that child, and the dead writer from the parent's owned keys.
 *  The child's own blocks are not touched: there is no pointer to reach them through, so whatever
 *  it held is already gone and only the reference to it can still be repaired.
 *
 *  Usage:
 *    java -cp Peergos.jar peergos.server.util.RemoveUnreachableChild \
 *         -username alice -path /alice/photos/holiday [-peergos-url http://localhost:8000] [-dry-run true]
 */
public class RemoveUnreachableChild {

    public static void main(String[] args) throws Exception {
        Crypto crypto = Main.initCrypto();
        Args a = Args.parse(args);
        String username = a.getArg("username");
        String pathArg = a.getArg("path");
        String url = a.getArg("peergos-url", "http://localhost:8000");
        boolean dryRun = a.getBoolean("dry-run", false);

        Path target = PathUtil.get(pathArg);
        if (target.getParent() == null)
            throw new IllegalStateException("Nothing to remove: " + pathArg + " has no parent directory");
        Path parentPath = target.getParent();
        String childName = target.getFileName().toString();

        NetworkAccess network = Builder.buildJavaNetworkAccess(new URL(url), ! url.startsWith("http://localhost"),
                Optional.empty(), Optional.empty()).get();

        String password;
        if (a.hasArg("PEERGOS_PASSWORD"))
            password = a.getArg("PEERGOS_PASSWORD");
        else {
            Console console = System.console();
            if (console == null)
                throw new IllegalStateException("No console to read a password from - pass -PEERGOS_PASSWORD");
            password = new String(console.readPassword("Enter password for " + username + ": "));
        }
        UserContext context = UserContext.signIn(username, password, Main::getMfaResponseCLI, network, crypto).get();

        boolean removed = removeUnreachableChild(context, parentPath, childName, dryRun);
        System.exit(removed ? 0 : 1);
    }

    /** @return true if the child was detached, or would have been on a dry run */
    public static boolean removeUnreachableChild(UserContext context,
                                                 Path parentPath,
                                                 String childName,
                                                 boolean dryRun) {
        NetworkAccess network = context.network;
        Crypto crypto = context.crypto;
        PublicKeyHash owner = context.signer.publicKeyHash;

        FileWrapper parent = context.getByPath(parentPath).join()
                .orElseThrow(() -> new IllegalStateException("Couldn't retrieve " + parentPath));
        if (! parent.isDirectory())
            throw new IllegalStateException(parentPath + " is not a directory");
        if (! parent.isWritable())
            throw new IllegalStateException("You don't have write access to " + parentPath);

        // the parent's own links name its children, so this does not resolve any child's writer
        Set<NamedAbsoluteCapability> children = parent.getPointer().fileAccess
                .getAllChildrenCapabilities(parent.version, parent.getPointer().capability, crypto.hasher, network)
                .join();
        List<NamedAbsoluteCapability> matching = children.stream()
                .filter(c -> c.name.name.equals(childName))
                .collect(Collectors.toList());
        if (matching.isEmpty())
            throw new IllegalStateException("No child named " + childName + " in " + parentPath + ". Present: "
                    + children.stream().map(c -> c.name.name).sorted().collect(Collectors.joining(", ")));

        List<AbsoluteCapability> toRemove = new ArrayList<>();
        Set<PublicKeyHash> deadWriters = new LinkedHashSet<>();
        for (NamedAbsoluteCapability child : matching) {
            PublicKeyHash childWriter = targetWriter(context, parent, child.cap);
            if (childWriter.equals(parent.writer()))
                throw new IllegalStateException(childName + " shares " + parentPath + "'s writing space, so it has"
                        + " no pointer of its own and cannot be the unreachable one. Delete it in the app instead.");
            if (pointerIsSet(context, childWriter))
                throw new IllegalStateException(childName + " is reachable - its writing space " + childWriter
                        + " still has a pointer. Delete it in the app rather than with this.");
            System.out.println("Found " + parentPath.resolve(childName) + " in writing space " + childWriter
                    + ", which has no pointer");
            toRemove.add(child.cap);
            deadWriters.add(childWriter);
        }

        if (dryRun) {
            System.out.println("Dry run: would detach " + toRemove.size() + " link(s) from " + parentPath
                    + " and drop " + deadWriters.size() + " owned key(s)");
            return true;
        }

        SigningPrivateKeyAndPublicHash parentSigner = parent.signingPair();
        network.synchronizer.applyComplexUpdate(owner, parentSigner, (version, committer) -> parent.getUpdated(version, network)
                        .thenCompose(fresh -> fresh.getPointer().fileAccess.removeChildren(version, committer, toRemove,
                                fresh.writableFilePointer(), Optional.of(parentSigner), network, crypto.random, crypto.hasher))
                        .thenCompose(afterRemoval -> Futures.reduceAll(deadWriters, afterRemoval,
                                (v, dead) -> CryptreeNode.deAuthoriseSigner(owner, parentSigner, dead, network, v, committer),
                                (x, y) -> y)))
                .join();

        System.out.println("Detached " + parentPath.resolve(childName) + " from " + parentPath);
        context.getByPath(parentPath.resolve(childName)).join().ifPresent(f -> {
            throw new IllegalStateException("It is still there - nothing was repaired");
        });
        System.out.println(parentPath + " should now open normally");
        return true;
    }

    /** A child in its own writing space is reached through a link node held in the parent's space, so
     *  the writer the parent names is the parent's own. Follow the link to the writer that matters.
     *  The link node is readable whatever state its target is in, which is what makes this work at all.
     */
    private static PublicKeyHash targetWriter(UserContext context, FileWrapper parent, AbsoluteCapability childCap) {
        NetworkAccess network = context.network;
        if (! childCap.writer.equals(parent.writer()))
            return childCap.writer;
        CryptreeNode node = network.getMetadata(parent.version.get(parent.writer()), childCap).join()
                .orElseThrow(() -> new IllegalStateException("Couldn't retrieve the link node for " + childCap));
        if (! node.getProperties(node.getParentKey(childCap.rBaseKey)).isLink)
            return childCap.writer;
        Set<NamedAbsoluteCapability> targets = node
                .getDirectChildrenCapabilities(childCap, parent.version, network).join();
        if (targets.size() != 1)
            throw new IllegalStateException("A link node should name exactly one target, not " + targets.size());
        return targets.iterator().next().cap.writer;
    }

    private static boolean pointerIsSet(UserContext context, PublicKeyHash writer) {
        return context.network.mutable
                .getPointerTarget(context.signer.publicKeyHash, writer, context.network.dhtClient)
                .join().updated.isPresent();
    }
}
