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
     * <p>
     * start/stop process
     * logging
     * configuring including bootstrapping
     */

    public static class Config {
        /**
         * Encapsulate IPFS configuration state.
         */
        public final Optional<String> bootstrapNode;
        public final int apiPort, gatewayPort, swarmPort;

        private Config(Optional<String> bootstrapNode, int apiPort, int gatewayPort, int swarmPort) {
            this.bootstrapNode = bootstrapNode;
            this.apiPort = apiPort;
            this.gatewayPort = gatewayPort;
            this.swarmPort = swarmPort;
        }

        private List<String[]> configCmds() {
            return Stream.of(
                    "config --json Experimental.Libp2pStreamMounting true",
                    String.format("config --json Addresses.API \"/ip4/127.0.0.1/tcp/%d\"", apiPort),
                    String.format("config --json Addresses.Gateway \"/ip4/127.0.0.1/tcp/%d\"", gatewayPort),
                    String.format("config --json Addresses.Swarm [\"/ip4/127.0.0.1/tcp/%d\",\"/ip6/::/tcp/%d\"]", swarmPort, swarmPort))
                    .map(e -> e.replaceAll("\"", "\\\""))  //escape "
                    .map(e -> e.split("\\s+"))
                    .collect(Collectors.toList());
        }
    }

    private static Config buildConfig(Args args) {
        Optional<String> bootstrapNodeAddress = Optional.ofNullable(args.getArg("ipfs-config-bootstrap-node", null));
        int apiPort = args.getInt("ipfs-config-api-port", 5001);
        int gatewayPort = args.getInt("ipfs-config-gateway-port", 8080);
        int swarmPort = args.getInt("ipfs-config-swarm-port", 4001);

        return new Config(bootstrapNodeAddress, apiPort, gatewayPort, swarmPort);
    }


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
    private Process process;


    public IpfsWrapper(Path ipfsPath, Path ipfsDir) {
        if (!Files.exists(ipfsDir))
            throw new IllegalStateException("Specified path to IPFS_DIR'" + ipfsDir + "' does not exist");
        if (!Files.exists(ipfsPath))
            throw new IllegalStateException("Specified path to ipfs binary '" + ipfsPath + "' does not exist");
        this.ipfsPath = ipfsPath;
        this.ipfsDir = ipfsDir;
        // add shutdown-hook to ensure ipfs daemon is killed on exit
        Runtime.getRuntime().addShutdownHook(new Thread(this::close));
    }


    public synchronized void config(Config config) {

        LOG().info("Initializing ipfs");
        runIpfsCmd("init");

        if (config.bootstrapNode.isPresent()) {
            LOG().info("Setting ipfs bootstrap node " + config.bootstrapNode);
            runIpfsCmd("bootstrap", "rm", "all");
            runIpfsCmd("bootstrap", "add", config.bootstrapNode.get());
        }

        LOG().info("Running ipfs config");
        for (String[] configCmd : config.configCmds()) {
            //ipfs config x y z
            runIpfsCmd(configCmd);
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
     * <p>
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
            } catch (InterruptedException ie) {
            }

            long current = System.currentTimeMillis();

            duration = (double) (current - start) / 1000.0;
        }

        // still not ready?
        throw new IllegalStateException("ipfs daemon is not ready after specified timeout " + timeoutSeconds + " seconds.");
    }

    @Override
    public synchronized void close() {
        if (process != null && process.isAlive())
            stop();
    }

    public synchronized void stop() {
        if (process == null || !process.isAlive())
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
            if (showLog || rc != 0) {
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

        IpfsWrapper ipfsWrapper = new IpfsWrapper(ipfsPath, ipfsDir);

        Config config = new Config(Optional.empty(), 5001, 8080, 4001);
        ipfsWrapper.config(config);

        new Thread(ipfsWrapper::run).start();
        ipfsWrapper.waitForDaemon(30);

        try {
            Thread.sleep(10_000);
        } catch (InterruptedException ie) {
        }

        ipfsWrapper.stop();
    }

    public static IpfsWrapper build(Args args) {
        //$IPFS_DIR, defaults to $PEERGOS_PATH/.ipfs
        Path ipfsDir = args.hasArg(IPFS_DIR) ?
                Paths.get(args.getArg(IPFS_DIR)) :
                args.fromPeergosDir("ipfs-name", DEFAULT_DIR_NAME);

        //ipfs exe defaults to $PEERGOS_PATH/ipfs
        Path ipfsExe = args.hasArg(IPFS_EXE) ?
                Paths.get(args.getArg(IPFS_EXE)) :
                args.fromPeergosDir("ipfs-exe", DEFAULT_IPFS_EXE);

        return new IpfsWrapper(ipfsExe, ipfsDir);
    }

    /**
     * Build an IpfsWrapper based on args.
     * <p>
     * Start running it in a sub-process.
     * <p>
     * Block until the ipfs-daemon is ready for requests.
     *
     * @param args
     * @param configure will init and bootstrap ipfs when true
     * @return
     */
    public static IpfsWrapper launch(Args args, boolean configure) {
        IpfsWrapper ipfs = IpfsWrapper.build(args);
        if (configure)
            ipfs.config(buildConfig(args));

        LOG().info("Starting ipfs daemon");

        new Thread(ipfs::run).start();

        int timeout = args.getInt("ipfs-timeout-seconds", 30);
        ipfs.waitForDaemon(timeout);
        return ipfs;
    }

}
