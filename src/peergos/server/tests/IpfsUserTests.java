package peergos.server.tests;

import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;
import peergos.server.*;
import peergos.server.storage.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;

@RunWith(Parameterized.class)
public class IpfsUserTests extends UserTests {

    private static Args args = buildArgs()
            .with("useIPFS", "true")
            .with("enable-gc", "true")
//            .with("gc.period.millis", "10000")
            .with("collect-metrics", "true")
            .with("metrics.address", "localhost")
            .with(IpfsWrapper.IPFS_BOOTSTRAP_NODES, ""); // no bootstrapping

    public IpfsUserTests(NetworkAccess network, UserService service) {
        super(network, service);
    }

    @Parameterized.Parameters()
    public static Collection<Object[]> parameters() throws Exception {
        UserService service = Main.PKI_INIT.main(args).localApi;
        return Arrays.asList(new Object[][] {
                {
                        Builder.buildJavaNetworkAccess(new URL("http://localhost:" + args.getInt("port")), false).join(),
                        service
                }
        });
    }

    @AfterClass
    public static void cleanup() {
        try {Thread.sleep(2000);}catch (InterruptedException e) {}
        Path peergosDir = args.fromPeergosDir("", "");
        System.out.println("Deleting " + peergosDir);
        deleteFiles(peergosDir.toFile());
    }

    @Override
    public Args getArgs() {
        return args;
    }

    public long getBlockstoreSize() {
        Path ipfsDir = args.fromPeergosDir("", ".ipfs").resolve("blocks");
        try {
            return Files.walk(ipfsDir)
                    .filter(p -> p.toFile().isFile() && p.getFileName().toString().endsWith(".data"))
                    .mapToLong(p -> p.toFile().length())
                    .sum();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
    @Test
    public void gcReclaimsSpace() {
        String username = generateUsername();
        String password = "password";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);
        // need to clear transactions otherwise blocks won't be GC'd for a day
        TransactionStore transactionStore = Builder.buildTransactionStore(args, Builder.getDBConnector(args.with("transactions-sql-file", "transactions.sql"),
                "transactions-sql-file"));
        transactionStore.clearOldTransactions(System.currentTimeMillis());
        gc();
        long sizeBefore = getBlockstoreSize();
        long usageBefore = context.getSpaceUsage().join();
        int filesize = 10 * 1024 * 1024;
        String filename = "file.bin";
        context.getUserRoot().join().uploadOrReplaceFile(filename, AsyncReader.build(new byte[filesize]),
                filesize, network, crypto, x -> {}).join();
        long sizeWithFile = getBlockstoreSize();
        Assert.assertTrue(sizeWithFile > sizeBefore + filesize);
        Threads.sleep(2_000); // Allow time for server to update usage
        long usageWithFile = context.getSpaceUsage().join();
        Assert.assertTrue(usageWithFile > usageBefore + filesize);

        Path filePath = PathUtil.get(username, filename);
        context.getByPath(filePath).join().get()
                .remove(context.getUserRoot().join(), filePath, context).join();
        transactionStore.clearOldTransactions(System.currentTimeMillis());
        List<Cid> open = transactionStore.getOpenTransactionBlocks();
        Assert.assertTrue(open.isEmpty());
        long usageAfter = context.getSpaceUsage().join();
        Assert.assertTrue(usageAfter == usageBefore);
        gc();
        long sizeAfterDelete = getBlockstoreSize();
        long diff = sizeAfterDelete - sizeBefore;
        Assert.assertTrue(diff < 20*1024); // Why not equal?
    }
}
