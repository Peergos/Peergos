package peergos.server.fuse;
import java.util.logging.*;
import peergos.server.util.Logging;

import peergos.shared.*;
import peergos.server.Start;
import peergos.server.tests.UserTests;
import peergos.shared.user.UserContext;
import peergos.server.util.Args;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class FuseProcess implements Runnable, AutoCloseable {
	private static final Logger LOG = Logging.LOG();

    private final PeergosFS peergosFS;
    private final Path mountPoint;
    private volatile boolean isFinished;
    private volatile boolean isClosed;


    public FuseProcess(PeergosFS peergosFS, Path mountPoint) {
        this.peergosFS = peergosFS;
        this.mountPoint = mountPoint;
    }

    @Override
    public void run() {
        while  (! isFinished) {
            synchronized (this) {
                try {
                    wait(1000);
                } catch (InterruptedException ie) {}
            }
        }
        isClosed = true;
    }

    public void start() {
        ensureNotFinished();

        boolean blocking = false;
        boolean debug = false;
        int transferBufferSize = 5*1024*1024;
        String[] fuseOpts = new String[]{"-o", "big_writes",
                "-o", "fsname=Peergos",
                "-o", "max_read="+transferBufferSize, "-o", "max_write="+transferBufferSize};
        peergosFS.mount(mountPoint, blocking, debug, fuseOpts);

        new Thread(this).start();
    }

    public void close() {
        if (isFinished)
            return;
        isFinished = true;
        synchronized (this) {
            notify();
        }
        LOG.info("CLOSE");
        while (! isClosed) {
            try {
                Thread.sleep(1000);
            }  catch (InterruptedException ie){}
            LOG.info("CALLING UNMOUNT");
            peergosFS.umount();
        }
        LOG.info("DONE");
    }

    private void ensureNotFinished() {
        if (isFinished)
            throw new IllegalStateException();
    }

    public static void main(String[] args) throws Exception {
        int WEB_PORT = 8000;
        int CORE_PORT = 9999;

        Args a = Args.parse(new String[]{"useIPFS", "false",
                "-port", Integer.toString(WEB_PORT),
                "-corenodePort", Integer.toString(CORE_PORT)});

        Start.LOCAL.main(a);

        a = Args.parse(args);
        String username = a.getArg("username", "test01");
        String password = a.getArg("password", "test01");
        String mountPath = a.getArg("mountPoint", "/tmp/peergos/tmp");

        Path path = Paths.get(mountPath);
        path = path.resolve(UUID.randomUUID().toString());
        path.toFile().mkdirs();

        LOG.info("\n\nPeergos mounted at "+ path+"\n\n");

        NetworkAccess network = NetworkAccess.buildJava(WEB_PORT).get();
        Crypto crypto = Crypto.initJava();
        UserContext userContext = UserTests.ensureSignedUp(username, password, network, crypto);
        PeergosFS peergosFS = new CachingPeergosFS( userContext);
        FuseProcess fuseProcess = new FuseProcess(peergosFS, path);

        Runtime.getRuntime().addShutdownHook(new Thread(()  -> fuseProcess.close()));

        fuseProcess.start();
    }
}
