package peergos.server;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * A utility class for managing the Peergos runtime including:
 * <p>
 * installing and updating Peergos
 * start/stop process
 */
public class PeergosManager implements AutoCloseable, Runnable {
    private static List<PeergosManager> ALL_INSTANCES = new ArrayList<>();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            for (PeergosManager ipfs : ALL_INSTANCES) {
                ipfs.close();
            }
        }));
    }

    private volatile boolean shouldBeRunning;

    public final Path peergosBinary;
    public final String[] args;
    private Process process;

    public PeergosManager(Path peergosBinary, String[] args) {
        this.peergosBinary = peergosBinary;
        this.args = args;
        // add shutdown-hook to ensure Peergos is killed on exit
        ALL_INSTANCES.add(this);
    }

    private synchronized void start() {
        if (process != null && process.isAlive())
            throw new IllegalStateException("Peergos is already running");
        process = startCommand(args);
    }

    @Override
    public synchronized void close() {
        if (process != null && process.isAlive())
            stop();
    }

    public synchronized void stop() {
        if (process == null || !process.isAlive())
            throw new IllegalStateException("Peergos is not running");

        shouldBeRunning = false;
        process.destroy();
    }

    /**
     * @param subCmd
     * @return
     */
    private Process startCommand(String... subCmd) {
        return startCommandRetry(5, subCmd);
    }

    private Process startCommandRetry(int retryCount, String... subCmd) {
        long sleepMs = 100;
        Process process  = null;
        for (int i = 0; i < retryCount; i++) {
            process = startCommandOnce(subCmd);
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException  ie){}
            try {
                if  (process.exitValue() ==  0)
                    return process;
            } catch (IllegalThreadStateException  ex)  {
                // still running
                return process;
            }
            sleepMs *= 2;
        }
        return process;
    }

    private Process startCommandOnce(String... subCmd) {
        LinkedList<String> list = new LinkedList<>(Arrays.asList(subCmd));
        list.addFirst(peergosBinary.toString());
        list.addFirst("-jar");
        list.addFirst("java");
        ProcessBuilder pb = new ProcessBuilder(list);
        try {
            return pb.start();
        } catch (IOException ioe) {
            throw new IllegalStateException(ioe.getMessage(), ioe);
        }
    }

    /**
     * Run Peergos and restart it if it dies.
     */
    public void run() {
        if (shouldBeRunning)
            throw new IllegalStateException("Cannot run Peergos: should already be running");

        shouldBeRunning = true;

        while (shouldBeRunning) {
            // start Peergos if it isn't running
            synchronized (this) {
                if (process == null || !process.isAlive())
                    start();
            }

            try {
                int rc = process.waitFor();
                if (rc != 0) {
                    System.err.println("IPFS exited with return-code " + rc);
                    Thread.sleep(1_000);
                }
            } catch (InterruptedException ie) { }
        }
    }

    public static PeergosManager build(String[] args) {
        Path peergosExe = Paths.get("PeergosServer.jar");

        System.out.println("Using Peergos binary " + peergosExe);
        return new PeergosManager(peergosExe, args);
    }

    public static PeergosManager launchAndManage(PeergosManager ipfs) {
        new Thread(ipfs).start();
        return ipfs;
    }

    /**
     * Build a PeergosManager based on args.
     * <p>
     * Start running it in a sub-process.
     *
     * Restart Peergos if it dies.
     *
     * @param args
     * @return
     */
    public static PeergosManager launch(String[] args) {
        PeergosManager peergos = PeergosManager.build(args);
        return launchAndManage(peergos);
    }

    public static Version getLatestVersion() {
        return new Version(0, 1, 0, "");
    }

    public static void install(Version v) {
        System.out.println("Installing Peergos version " + v);

    }

    public static void main(String[] args) {
        if (args.length == 0)
            args = new String[]{"-pki", "-logToConsole", "true", "-useIPFS", "false",
                    "-peergos.password", "testpassword", "pki.keygen.password", "pw2", "pki.keyfile.password", "pw3"};

        while (true) {
            Version current = getLatestVersion();
            install(current);
            PeergosManager instance = launch(args);
            while (true) {
                // check if a new version is available
                Version latest = getLatestVersion();
                if (current.isBefore(latest)) {
                    instance.stop();
                    ALL_INSTANCES.remove(instance);
                    break;
                }
                try {
                    Thread.sleep(24 * 3_600_000);
                } catch (InterruptedException e) {}
            }
        }
    }
}
