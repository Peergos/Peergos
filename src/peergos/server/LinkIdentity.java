package peergos.server;

import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;

import java.io.*;
import java.nio.file.*;

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
        UserContext context = UserContext.signIn(username, password, network, crypto).join();
        String usernameB = a.getArg("service-username");
        String serviceB = a.getArg("service");
        boolean encrypted = a.getBoolean("encrypted");
        boolean publish = ! encrypted && a.getBoolean("publish", false);

        IdentityLinkProof proof = IdentityLinkProof.buildAndSign(context.signer, username, usernameB, serviceB);
        if (encrypted)
            proof = proof.withKey(SymmetricKey.random());

        uploadProof(proof, context, publish);

        System.out.println("Successfully generated, signed and uploaded identity link.");
        System.out.println("Post the following text to the alternative service:\n");
        FileWrapper proofFile = context.getByPath(Paths.get(username, ".profile", "ids", proof.getFilename())).join().get();
        System.out.println(proof.postText(proof.getUrlToPost(proofFile, publish)));

        String postUrl = console.readLine("\nEnter the URL for the post on the alternative service:");
        proof = proof.withPostUrl(postUrl);
        uploadProof(proof, context, publish);
        System.out.println("Successfully linked to post on alternative service.");
    }

    private static void uploadProof(IdentityLinkProof proof, UserContext context, boolean makePublic) {
        Path subPath = Paths.get(".profile", "ids");
        FileWrapper idsDir = context.getUserRoot().join().getOrMkdirs(subPath, context.network, true, context.crypto).join();
        String filename = proof.getFilename();

        byte[] raw = proof.serialize();
        idsDir.uploadOrReplaceFile(filename, AsyncReader.build(raw), raw.length,
                context.network, context.crypto, x -> {}, context.crypto.random.randomBytes(32)).join();

        if (makePublic)
            context.makePublic(context.getByPath(Paths.get(context.username).resolve(subPath).resolve(filename)).join().get()).join();
    }
}
