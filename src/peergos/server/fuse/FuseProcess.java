package peergos.server.fuse;
import java.util.logging.*;
import peergos.server.util.Logging;

import java.nio.file.Path;

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

        new Thread(this, "Fuse process").start();
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
}
