package peergos.server.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static peergos.server.util.Logging.LOG;

public class IpfsWrapper implements AutoCloseable, Runnable {
    /**
     * A utility class for managing the IPFS daemon runtime including:
     *
     * start/stop process
     * logging
     * configuring including bootstrapping
     */

    private static final String IPFS_DIR = "IPFS_PATH";
    private static final String IPFS_EXE = "ipfs-exe-path";

    private static final String DEFAULT_DIR_NAME = ".ipfs";
    private static final String DEFAULT_IPFS_EXE = "ipfs";

    private volatile boolean shouldBeRunning;

    /**
     * Path to IPFS binary and IPFS_DIR
     */
    public final Path ipfsPath, ipfsDir;
    //ipfs config commands (everything after config)
    private final List<List<String>> configCmds;
    private Process process;


    public IpfsWrapper(Path ipfsPath, Path ipfsDir, List<List<String>> configCmds) {
        if (!Files.exists(ipfsDir))
            throw new IllegalStateException("Specified path to IPFS_DIR'" + ipfsDir + "' does not exist");
        if (!Files.exists(ipfsPath))
            throw new IllegalStateException("Specified path to ipfs binary '" + ipfsPath + "' does not exist");
        this.ipfsPath = ipfsPath;
        this.ipfsDir = ipfsDir;
        this.configCmds = new ArrayList<>(configCmds);

        // add shutdown-hook to ensure ipfs daemon is killed on exit
        Runtime.getRuntime().addShutdownHook(new Thread(this::close));
    }


    public synchronized void setup() {
        //ipfs init
        runIpfsCmd("init");

        for (List<String> configCmd : configCmds) {
            LinkedList<String> list = new LinkedList<>(configCmd);
            list.addFirst("config");
            //ipfs config ...
            runIpfsCmd(list.toArray(new String[0]));
        }
    }

    private synchronized void start() {
        if (process != null && process.isAlive())
            throw new IllegalStateException("ipfs daemon is already running");
        //ipfs daemon
        LOG().info("Starting ipfs daemon");
        process = startIpfsCmd("daemon");

        new Thread(() -> Logging.log(process.getInputStream(), "IPFS out : ")).start();
        new Thread(() -> Logging.log(process.getErrorStream(), "IPFS err : ")).start();
    }

    /**
     * Wait until the ipfs id comamnd returns a sensible response.
     *
     * The ipfs daemon can take up to 30 seconds to start
     * responding to requests once the daemon is started.
     */
    private void waitForDaemon(int timeoutSeconds) {
        long start = System.currentTimeMillis();
        double duration = 0;

        while (duration < timeoutSeconds) {

            // ready now?
            try {
                runIpfsCmd(false, "id");
                // ready
                return;
            } catch (IllegalStateException ile) {
                // not ready
            }

            try {
                Thread.sleep(1000);
            } catch (InterruptedException  ie) {}

            long current = System.currentTimeMillis();

            duration = (double) (current - start) / 1000.0;
        }

        // still not ready?
        throw new IllegalStateException("ipfs daemon is not ready after specified timeout "+ timeoutSeconds +" seconds.");
    }

    @Override
    public synchronized void close() {
        if (process != null && process.isAlive())
            stop();
    }

    public synchronized void stop() {
        if (process == null || ! process.isAlive())
            throw new IllegalStateException("ipfs daemon is not running");

        LOG().info("Stopping ipfs daemon");
        shouldBeRunning = false;
        process.destroy();
    }

    private Process startIpfsCmd(String... subCmd) {
        LinkedList<String> list = new LinkedList<>(Arrays.asList(subCmd));
        list.addFirst(ipfsPath.toString());
        ProcessBuilder pb = new ProcessBuilder(list);
        pb.environment().put(IPFS_DIR, ipfsDir.toString());
        try {
            return pb.start();
        } catch (IOException ioe) {
            throw new IllegalStateException(ioe.getMessage(), ioe);
        }
    }

    private void runIpfsCmd(String... subCmd) {
        boolean showLog = false;
        runIpfsCmd(showLog, subCmd);
    }

    private void runIpfsCmd(boolean showLog, String... subCmd) {
        Process process = startIpfsCmd(subCmd);
        try {
            int rc = process.waitFor();

            String cmd = Stream.of(subCmd).collect(Collectors.joining(" ", "ipfs ", ""));
            if (showLog) {
                Logging.log(process.getInputStream(), cmd + " out : ");
                Logging.log(process.getErrorStream(), cmd + " err : ");
            }

            if (rc != 0) {
                throw new IllegalStateException("ipfs " + Arrays.asList(subCmd) + " returned exit-code " + rc);
            }

        } catch (InterruptedException ioe) {
            throw new IllegalStateException(ioe.getMessage(), ioe);
        }
    }


    /**
     * Run ``ipfs daemon`` and restart it if it dies.
     */
    public void run() {
        if (shouldBeRunning)
            throw new IllegalStateException("Cannot run ipfs daemon: should already be running");

        shouldBeRunning = true;

        while (shouldBeRunning) {
            // start daemon if it isn't running
            synchronized (this) {
                if (process == null || !process.isAlive())
                    start();
            }

            try {
                int rc = process.waitFor();
                if (rc != 0) {
                    LOG().warning("IPFS exited with return-code " + rc);
                }
            } catch (InterruptedException ie) {
            }
        }
    }

    public static void main(String[] args) throws Exception {

        Path ipfsPath = Paths.get(
                System.getenv().getOrDefault("GOPATH", "/home/chris/go"),
                "src/github.com/ipfs/go-ipfs/cmd/ipfs/ipfs");
        Path ipfsDir = Files.createTempDirectory(null);

        Logging.init(Paths.get("log.log"), 1_000_000, 1, false, true, true);

        IpfsWrapper ipfsWrapper = new IpfsWrapper(ipfsPath, ipfsDir, Collections.emptyList());
        ipfsWrapper.setup();
        new Thread(ipfsWrapper::run).start();
        ipfsWrapper.waitForDaemon(30);

        try {
            Thread.sleep(10_000);
        } catch (InterruptedException ie){}

        ipfsWrapper.stop();
    }

    public static IpfsWrapper build(Args args,  boolean setup) {
        //$IPFS_DIR, defaults to $PEERGOS_PATH/.ipfs
        Path ipfsDir =  args.hasArg(IPFS_DIR) ?
                Paths.get(args.getArg(IPFS_DIR)) :
                args.fromPeergosDir("ipfs-name", DEFAULT_DIR_NAME);

        //ipfs exe defaults to $PEERGOS_PATH/ipfs
        Path  ipfsExe = args.hasArg(IPFS_EXE) ?
                Paths.get(args.getArg(IPFS_EXE)) :
                args.fromPeergosDir("ipfs-exe", DEFAULT_IPFS_EXE);

        // TODO
        List<List<String>> config = setup ? Collections.emptyList():  Collections.emptyList();

        return new IpfsWrapper(ipfsExe, ipfsDir, config);
    }

    /**
     * Build an IpfsWrapper based on args.
     *
     * Start running it in a sub-process.
     *
     * Block until the ipfs-daemon is ready for requests.
     *
     * @param args
     * @param setup will init and bootstrap when true
     * @return
     */
    public static IpfsWrapper launch(Args args,  boolean setup) {
        IpfsWrapper ipfs = IpfsWrapper.build(args, setup);

        LOG().info("Starting ipfs daemon");

        new Thread(ipfs::run).start();

        int timeout = args.getInt("ipfs-timeout-seconds", 30);
        ipfs.waitForDaemon(timeout);
        return ipfs;
    }

}
