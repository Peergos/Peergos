package peergos.server.tests;

import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;
import peergos.server.*;
import peergos.server.util.*;
import peergos.shared.social.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;

import java.nio.file.*;
import java.util.*;

@RunWith(Parameterized.class)
public class RamUserTests extends UserTests {
    private static Args args = buildArgs().with("useIPFS", "false");

    public RamUserTests(Args args) {
        super(args);
    }

    @Parameterized.Parameters()
    public static Collection<Object[]> parameters() {
        return Arrays.asList(new Object[][] {
                {args}
        });
    }

    @BeforeClass
    public static void init() {
        Main.PKI_INIT.main(args);
    }

    @Test
    public void revokeWriteAccessToTree() throws Exception {
        String username1 = generateUsername();
        String password = "test";
        UserContext user1 = PeergosNetworkUtils.ensureSignedUp(username1, password, network, crypto);
        FileWrapper user1Root = user1.getUserRoot().get();

        String folder1 = "folder1";
        user1Root.mkdir(folder1, user1.network, false, crypto).join();

        String folder11 = "folder1.1";
        user1.getByPath(Paths.get(username1, folder1)).join().get()
                .mkdir(folder11, user1.network, false, crypto).join();

        String filename = "somedata.txt";
        // write empty file
        byte[] data = new byte[0];
        user1.getByPath(Paths.get(username1, folder1, folder11)).join().get()
                .uploadOrOverwriteFile(filename, new AsyncReader.ArrayBacked(data), data.length, user1.network,
                crypto, l -> {}, crypto.random.randomBytes(32)).get();

        // create 2nd user and friend user1
        String username2 = generateUsername();
        UserContext user2 = PeergosNetworkUtils.ensureSignedUp(username2, password, network, crypto);
        user2.sendInitialFollowRequest(username1).join();
        List<FollowRequestWithCipherText> incoming = user1.getSocialState().join().pendingIncoming;
        user1.sendReplyFollowRequest(incoming.get(0), true, true).join();
        user2.getSocialState().join();

        user1.shareWriteAccessWith(Paths.get(username1, folder1), Collections.singleton(username2)).join();

        user1.unShareWriteAccess(Paths.get(username1, folder1), Collections.singleton(username2)).join();
        // check user1 can still log in
        UserContext freshUser1 = PeergosNetworkUtils.ensureSignedUp(username1, password, network, crypto);
    }
}
