package peergos.server.tests;

import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;
import peergos.server.*;
import peergos.server.storage.admin.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.user.*;

import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

@RunWith(Parameterized.class)
public class TokenSignupTests {

    private static Args args = UserTests.buildArgs()
            .with("useIPFS", "false")
            .with("max-users", "1");
    protected static final Crypto crypto = Main.initCrypto();

    private final NetworkAccess network;
    private final UserService service;

    public TokenSignupTests(NetworkAccess network, UserService service) {
        this.network = network;
        this.service = service;
    }

    @Parameterized.Parameters()
    public static Collection<Object[]> parameters() throws Exception {
        UserService service = Main.PKI_INIT.main(args).localApi;
        WriteSynchronizer synchronizer = new WriteSynchronizer(service.mutable, service.storage, crypto.hasher);
        MutableTree mutableTree = new MutableTreeImpl(service.mutable, service.storage, crypto.hasher, synchronizer);
        // use actual http messager
        ServerMessager.HTTP serverMessager = new ServerMessager.HTTP(new JavaPoster(new URI("http://localhost:" + args.getArg("port")).toURL(), false));
        NetworkAccess network = new NetworkAccess(service.coreNode, service.account, service.social, service.storage,
                service.bats, Optional.empty(), service.mutable, mutableTree, synchronizer, service.controller, service.usage,
                serverMessager, service.crypto.hasher,
                Arrays.asList("peergos"), false);
        return Arrays.asList(new Object[][] {
                {network, service}
        });
    }

    @AfterClass
    public static void cleanup() {
        try {Thread.sleep(2000);}catch (InterruptedException e) {}
        Path peergosDir = args.fromPeergosDir("", "");
        System.out.println("Deleting " + peergosDir);
        UserTests.deleteFiles(peergosDir.toFile());
    }

    @Test
    public void signupWithToken() {
        String username = "q";
        String password = "test";
        String badtoken = "notvalid";
        // invalid token fails
        try {
            UserContext.signUp(username, password, badtoken, network, crypto).join();
            throw new RuntimeException("Shouldn't get here!");
        } catch (CompletionException e) {}

        String token = ((Admin)service.controller).generateSignupToken(crypto.random);
        UserContext.signUp(username, password, token, network, crypto).join();
    }
}
