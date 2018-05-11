package peergos.server;

import peergos.server.corenode.*;
import peergos.server.mutable.*;
import peergos.server.social.*;
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

        ContentAddressedStorage nonWriteThroughIpfs = new NonWriteThroughStorage(source.dhtClient);
        MutablePointers nonWriteThroughPointers = new NonWriteThroughMutablePointers(source.mutable, nonWriteThroughIpfs);
        NonWriteThroughCoreNode nonWriteThroughCoreNode = new NonWriteThroughCoreNode(source.coreNode, nonWriteThroughIpfs);
        NonWriteThroughSocialNetwork nonWriteThroughSocial = new NonWriteThroughSocialNetwork(source.social, nonWriteThroughIpfs);
        MutableTreeImpl nonWriteThroughTree = new MutableTreeImpl(nonWriteThroughPointers, nonWriteThroughIpfs);
        NetworkAccess nonWriteThrough = new NetworkAccess(nonWriteThroughCoreNode,
                nonWriteThroughSocial,
                nonWriteThroughIpfs,
                nonWriteThroughPointers,
                nonWriteThroughTree, source.usernames, false);

        String username = args[0];
        Console console = System.console();
        String password = new String(console.readPassword("Enter password for " + username + ":"));
        UserContext context = UserContext.signIn(username, password, nonWriteThrough, crypto).get();

        // invert the following two lines to actually commit the experiment
        experiment(username, context, nonWriteThrough);
//        experiment(username, context, source);

        // Can we still log in?
        UserContext context2 = UserContext.signIn(username, password, nonWriteThrough, crypto).get();
        System.out.println(context2.getUserRoot().get().getName());
    }

    private static void experiment(String username,
                                   UserContext context,
                                   NetworkAccess network) throws Exception {
        // Do something dangerous (you only live once)
        Set<PublicKeyHash> ownedKeys = WriterData.getOwnedKeysRecursive(username, network.coreNode, network.mutable, network.dhtClient);
        for (PublicKeyHash ownedKey : ownedKeys) {
            if (ownedKey.equals(context.signer.publicKeyHash))
                continue; // only the writer has a tree
            CommittedWriterData existing = WriterData.getWriterData(ownedKey, network.mutable, network.dhtClient).get();
            if (existing.props.tree.isPresent())
                continue;
            SecretSigningKey signingKey = context.getUserRoot().get().getEntryWriterKey().get();
            SigningPrivateKeyAndPublicHash writer = new SigningPrivateKeyAndPublicHash(ownedKey, signingKey);
            existing.props.migrateToChamp(writer, existing.hash, network, res -> {}).get();
        }
    }
}
