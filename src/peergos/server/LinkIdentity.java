package peergos.server;

import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.io.ipfs.bases.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public class LinkIdentity {

    /** Link a peergos account to an account on an external service,
     *  where you can post textual content up to 280 characters long.
     *
     * @param a
     * @param network
     * @param crypto
     */
    public static void link(Args a, NetworkAccess network, Crypto crypto) {
        String username = a.getArg("username");
        Console console = System.console();
        String password = new String(console.readPassword("Enter password for " + username + ":"));
        UserContext context = UserContext.signIn(username, password, Main::getMfaResponseCLI, network, crypto).join();
        String usernameB = a.getArg("service-username");
        String serviceB = a.getArg("service");
        if (! Pattern.compile(IdentityLink.KnownService.Peergos.usernameRegex).matcher(username).matches())
            throw new IllegalStateException("Invalid username for Peergos");
        IdentityLink.IdentityService B = IdentityLink.IdentityService.parse(serviceB);
        if (! Pattern.compile(B.usernameRegex()).matcher(usernameB).matches())
            throw new IllegalStateException("Invalid username for " + serviceB);

        boolean encrypted = a.getBoolean("encrypted");
        boolean publish = ! encrypted && a.getBoolean("publish", false);

        IdentityLinkProof proof = IdentityLinkProof.buildAndSign(context.signer, username, usernameB, serviceB).join();
        if (encrypted)
            proof = proof.withKey(SymmetricKey.random());

        uploadProof(proof, context, publish);

        System.out.println("Successfully generated, signed and uploaded identity link.");
        System.out.println("Post the following text to the alternative service:\n");
        FileWrapper proofFile = context.getByPath(PathUtil.get(username, ".profile", "ids", proof.getFilename())).join().get();
        boolean isLocalhost = a.getArg("peergos-url").startsWith("http://localhost");
        String publicPeergosUrl = isLocalhost ? "https://peergos.net" : a.getArg("peergos-url");
        System.out.println(proof.postText(proof.getUrlToPost(publicPeergosUrl, proofFile, publish)));

        String postUrl = console.readLine("\nEnter the URL for the post on the alternative service:");
        proof = proof.withPostUrl(postUrl);
        uploadProof(proof, context, publish);
        System.out.println("Successfully linked to post on alternative service.");
    }

    private static void uploadProof(IdentityLinkProof proof, UserContext context, boolean makePublic) {
        Path subPath = PathUtil.get(".profile", "ids");
        FileWrapper idsDir = context.getUserRoot().join().getOrMkdirs(subPath, context.network, true, context.mirrorBatId(), context.crypto).join();
        String filename = proof.getFilename();

        byte[] raw = proof.serialize();
        idsDir.uploadOrReplaceFile(filename, AsyncReader.build(raw), raw.length, context.network, context.crypto, x -> {}).join();

        if (makePublic)
            context.makePublic(context.getByPath(PathUtil.get(context.username).resolve(subPath).resolve(filename)).join().get()).join();
    }

    public static void verify(Args a, NetworkAccess network) {
        String username = a.getArg("username");
        String usernameB = a.getArg("service-username");
        String serviceB = a.getArg("service");
        String sigb58 = a.getArg("signature");
        IdentityLink claim = new IdentityLink(username, IdentityLink.IdentityService.parse("Peergos"),
                usernameB, IdentityLink.IdentityService.parse(serviceB));
        IdentityLinkProof proof = new IdentityLinkProof(claim, Base58.decode(sigb58), Optional.empty(), Optional.empty());
        Optional<PublicKeyHash> idKeyHash = network.coreNode.getPublicKeyHash(username).join();
        if (idKeyHash.isEmpty())
            throw new IllegalStateException("Unknown user: " + username);
        Optional<PublicSigningKey> idKey = network.dhtClient.getSigningKey(idKeyHash.get(), idKeyHash.get()).join();
        if (idKey.isEmpty())
            throw new IllegalStateException("Couldn't retrieve key for " + username);

        proof.isValid(idKey.get()).join();
        System.out.println("Identity link proof is correct - it was signed by the Peergos user " + username);
    }
}
