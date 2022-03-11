package peergos.server.tests;

import org.junit.BeforeClass;
import org.junit.Test;
import peergos.server.Builder;
import peergos.server.Main;
import peergos.server.util.Args;
import peergos.shared.Crypto;
import peergos.shared.NetworkAccess;
import peergos.shared.user.App;
import peergos.shared.user.UserContext;
import peergos.shared.user.fs.FileWrapper;
import peergos.shared.util.*;

import java.net.URL;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public class WriterTest {
    private static Args args = UserTests.buildArgs().with("useIPFS", "false");
    private static Random random = new Random();
    private static final Crypto crypto = Main.initCrypto();
    private NetworkAccess network = null;
    private UserContext emailBridgeContext = null;
    private static final String emailBridgeUsername = "bridge";
    private static final String emailBridgePassword = "notagoodone";
    private static final String url = "http://localhost:" + args.getArg("port");
    private static final boolean isPublicServer = false;

    public WriterTest() throws Exception{
        network = Builder.buildJavaNetworkAccess(new URL(url), isPublicServer).get();
        emailBridgeContext = PeergosNetworkUtils.ensureSignedUp(emailBridgeUsername, emailBridgePassword, network, crypto);
    }

    @BeforeClass
    public static void init() {
        Main.PKI_INIT.main(args);
    }

    protected String generateUsername() {
        return "test" + Math.abs(random.nextInt() % 1_000_000);
    }

    @Test
    public void sendTest() throws Exception {
        String password = "notagoodone";
        UserContext userContext = PeergosNetworkUtils.ensureSignedUp("a-" + generateUsername(), password, network, crypto);

        List<UserContext> shareeUsers = Arrays.asList(emailBridgeContext);
        PeergosNetworkUtils.friendBetweenGroups(Arrays.asList(userContext), shareeUsers);

        App emailApp = App.init(userContext, "email").join();
        List<String> dirs = Arrays.asList("pending");// if i add this then it works , "pending/output");
        Path attachmentsDir = PathUtil.get(".apps", "email", "data");
        for(String dir : dirs) {
            Path dirFromHome = attachmentsDir.resolve(PathUtil.get(dir));
            Optional<FileWrapper> homeOpt = userContext.getByPath(userContext.username).join();
            homeOpt.get().getOrMkdirs(dirFromHome, userContext.network, true, userContext.mirrorBatId(), userContext.crypto).join();
        }
        
        //the shareWriteAccessWith call leads to the problem...
        List<String> sharees = Arrays.asList(emailBridgeContext.username);
        String dirStr = userContext.username + "/.apps/email/data/pending";
        Path directoryPath = peergos.client.PathUtils.directoryToPath(dirStr.split("/"));
        userContext.shareWriteAccessWith(directoryPath, sharees.stream().collect(Collectors.toSet())).join();

        byte[] bytes = randomData(10);
        Path filePath = PathUtil.get("pending", "output", "data.dat");
        emailApp.writeInternal(filePath, bytes, null).join();
    }

    @Test
    public void deleteTest() throws Exception {
        String password = "notagoodone";
        UserContext userContext = PeergosNetworkUtils.ensureSignedUp("a-" + generateUsername(), password, network, crypto);

        List<UserContext> shareeUsers = Arrays.asList(emailBridgeContext);
        PeergosNetworkUtils.friendBetweenGroups(Arrays.asList(userContext), shareeUsers);

        App emailApp = App.init(userContext, "email").join();
        List<String> dirs = Arrays.asList("pending", "pending/inbox");
        Path attachmentsDir = PathUtil.get(".apps", "email", "data");
        for(String dir : dirs) {
            Path dirFromHome = attachmentsDir.resolve(PathUtil.get(dir));
            Optional<FileWrapper> homeOpt = userContext.getByPath(userContext.username).join();
            homeOpt.get().getOrMkdirs(dirFromHome, userContext.network, true, userContext.mirrorBatId(), userContext.crypto).join();
        }
        List<String> sharees = Arrays.asList(emailBridgeContext.username);
        String dirStr = userContext.username + "/.apps/email/data/pending";
        Path directoryPath = peergos.client.PathUtils.directoryToPath(dirStr.split("/"));
        userContext.shareWriteAccessWith(directoryPath, sharees.stream().collect(Collectors.toSet())).join();

        byte[] bytes = randomData(100);
        Path filePath = PathUtil.get("pending/outbox/data.id");
        emailApp.writeInternal(filePath, bytes, null).join();

        String path = userContext.username + "/.apps/email/data/pending/outbox";
        Optional<FileWrapper> directory = emailBridgeContext.getByPath(path).get();

        Set<FileWrapper> files = directory.get().getChildren(emailBridgeContext.crypto.hasher, emailBridgeContext.network).get();
        for (FileWrapper file : files) {
            Path pathToFile = PathUtil.get(path).resolve(file.getName());
            file.remove(directory.get(), pathToFile, emailBridgeContext).get();
        }
    }

    public static byte[] randomData(int length) {
        byte[] data = new byte[length];
        random.nextBytes(data);
        return data;
    }
}
