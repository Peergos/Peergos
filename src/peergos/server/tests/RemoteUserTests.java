package peergos.server.tests;

import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;
import peergos.shared.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;

import java.net.*;
import java.util.*;

@RunWith(Parameterized.class)
public class RemoteUserTests {

    private final NetworkAccess network;
    private final Crypto crypto = Crypto.initJava();

    public RemoteUserTests(String remoteUrl) throws Exception {
        this.network = NetworkAccess.buildJava(new URL(remoteUrl)).get();
    }

    @Parameterized.Parameters(name = "{index}: {0}")
    public static Collection<Object[]> parameters() {
        return Arrays.asList(new Object[][] {
                {"https://demo.peergos.net"}
        });
    }

    @Test
    public void loginAndCleanADirectory() throws Exception {
        String username = "ianopolous";
        String password = "notmypassword";
        UserContext context = ensureSignedUp(username, password, network, crypto);
        FileTreeNode userRoot = context.getUserRoot().get();
        FileTreeNode dirToFixChildLinks = context.getByPath("/" + username + "/peergos").get().get();
        dirToFixChildLinks.cleanUnreachableChildren(context).get();
        Set<FileTreeNode> children = dirToFixChildLinks.getChildren(context).get();
        // Do stuff
        System.out.println(userRoot);
    }

    public static UserContext ensureSignedUp(String username, String password, NetworkAccess network, Crypto crypto) throws Exception {
        return UserContext.ensureSignedUp(username, password, network, crypto).get();
    }
}
