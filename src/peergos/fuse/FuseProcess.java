package peergos.fuse;

import peergos.crypto.*;
import peergos.server.Start;
import peergos.tests.UserTests;
import peergos.user.UserContext;
import peergos.util.Args;

import java.io.IOException;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class FuseProcess implements Runnable, AutoCloseable {

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
        String[] fuseOpts = new String[]{"-o", "big_writes", "-o", "large_read",
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
        System.out.println("CLOSE");
        while (! isClosed) {
            try {
                Thread.sleep(1000);
            }  catch (InterruptedException ie){}
            System.out.println("CALLING UNMOUNT");
            peergosFS.umount();
        }
        System.out.println("DONE");
    }

    private void ensureNotFinished() {
        if (isFinished)
            throw new IllegalStateException();
    }

    static void setFinalStatic(Field field, Object newValue) throws Exception {
        field.setAccessible(true);

        Field modifiersField = Field.class.getDeclaredField("modifiers");
        modifiersField.setAccessible(true);
        modifiersField.setInt(field, field.getModifiers() & ~Modifier.FINAL);

        field.set(null, newValue);
    }

    public static void main(String[] args) throws Exception {
        int WEB_PORT = 8000;
        int CORE_PORT = 9999;

        Args a = Args.parse(new String[]{"useIPFS", "false",
                "-port", Integer.toString(WEB_PORT),
                "-corenodePort", Integer.toString(CORE_PORT)});

        Start.local(a);

        // TODO find a faster high quality randomness source
        setFinalStatic(TweetNaCl.class.getDeclaredField("prng"), new Random());

        a = Args.parse(args);
        String username = a.getArg("username", "test02");
        String password = a.getArg("password", "test02");
        String mountPath = a.getArg("mountPoint", "/tmp/peergos/tmp");

        Path path = Paths.get(mountPath);
        path = path.resolve(UUID.randomUUID().toString());
        path.toFile().mkdirs();

        System.out.println("\n\nPeergos mounted at "+ path+"\n\n");

        UserContext userContext = UserTests.ensureSignedUp(username, password, WEB_PORT);
        PeergosFS peergosFS = new CachingPeergosFS(userContext);
        FuseProcess fuseProcess = new FuseProcess(peergosFS, path);

        Runtime.getRuntime().addShutdownHook(new Thread(()  -> fuseProcess.close()));

        fuseProcess.start();
    }
}
