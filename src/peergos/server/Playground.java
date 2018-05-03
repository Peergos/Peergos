package peergos.server;

import peergos.server.corenode.*;
import peergos.server.mutable.*;
import peergos.server.storage.*;
import peergos.shared.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.random.*;
import peergos.shared.mutable.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/**
 *  Use this class to experiment with existing data without committing any changes or writing any data to disk
 */
public class Playground {

    public static void main(String[] args) throws Exception {
        Crypto crypto = Crypto.initJava();
        NetworkAccess source = NetworkAccess.buildJava(new URL("https://demo.peergos.net")).get();

        ContentAddressedStorage nonWriteThroughIpfs = new NonWriteThroughStorage(source.dhtClient, new RAMStorage());
        NonWriteThroughCoreNode nonWriteThroughCoreNode = new NonWriteThroughCoreNode(source.coreNode, nonWriteThroughIpfs);
        MutablePointers nonWriteThroughPointers = new NonWriteThroughMutablePointers(source.mutable, nonWriteThroughIpfs);
        MutableTreeImpl nonWriteThroughTree = new MutableTreeImpl(nonWriteThroughPointers, nonWriteThroughIpfs);
        NetworkAccess nonWriteThrough = new NetworkAccess(nonWriteThroughCoreNode,
                nonWriteThroughIpfs,
                nonWriteThroughPointers,
                nonWriteThroughTree, source.usernames, false);

        String username = args[0];
        Console console = System.console();
        String password = new String(console.readPassword("Enter password for " + username + ":"));
        UserContext context = UserContext.signIn(username, password, nonWriteThrough, crypto).get();
        // Do something
        Set<PublicKeyHash> ownedKeys = WriterData.getOwnedKeysRecursive(username, nonWriteThroughCoreNode, nonWriteThroughPointers, nonWriteThroughIpfs);
        for (PublicKeyHash ownedKey : ownedKeys) {
            if (ownedKey.equals(context.signer.publicKeyHash))
                continue; // only the writer has a tree
            CommittedWriterData existing = WriterData.getWriterData(ownedKey, nonWriteThroughPointers, nonWriteThroughIpfs).get();
            if (existing.props.tree.isPresent())
                continue;
            SecretSigningKey signingKey = context.getUserRoot().get().getEntryWriterKey().get();
            SigningPrivateKeyAndPublicHash writer = new SigningPrivateKeyAndPublicHash(ownedKey, signingKey);
            // invert the following two lines to actually commit the migration
            existing.props.migrateToChamp(writer, existing.hash, nonWriteThrough, res -> {}).get();
//            existing.props.migrateToChamp(writer, existing.hash, source, res -> {}).get();
        }
        // Can we still log in?
        UserContext context2 = UserContext.signIn(username, password, nonWriteThrough, crypto).get();
        System.out.println(context2.getUserRoot().get().getName());
    }
}
