package peergos.server.storage;

import peergos.server.util.*;
import peergos.shared.io.ipfs.multiaddr.MultiAddress;
import peergos.shared.storage.*;
import peergos.shared.user.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.logging.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static peergos.server.util.Logging.LOG;
import static peergos.server.util.AddressUtil.getLocalAddress;

public class IpfsWrapper implements AutoCloseable, Runnable {
    /**
     * A utility class for managing the IPFS daemon runtime including:
     * <p>
     * start/stop process
     * logging
     * configuring including bootstrapping
     */
    public static final String IPFS_BOOTSTRAP_NODES = "ipfs-config-bootstrap-node-list";

    public static class Config {
        /**
         * Encapsulate IPFS configuration state.
         */
        public final Optional<List<MultiAddress>> bootstrapNode;
        public final int apiPort, gatewayPort, swarmPort;

        public Config(Optional<List<MultiAddress>> bootstrapNode, int apiPort, int gatewayPort, int swarmPort) {
            this.bootstrapNode = bootstrapNode;
            this.apiPort = apiPort;
            this.gatewayPort = gatewayPort;
            this.swarmPort = swarmPort;
        }

        private List<String[]> configCmds(boolean quoteEscape) {
            return Stream.of(
                    "config --json Experimental.Libp2pStreamMounting true",
                    "config --json Experimental.P2pHttpProxy true",
                    "config --json Experimental.PreferTLS true",
                    String.format("config Addresses.API /ip4/127.0.0.1/tcp/%d", apiPort),
                    String.format("config Addresses.Gateway /ip4/127.0.0.1/tcp/%d", gatewayPort),
                    String.format("config --json Addresses.Swarm [\"/ip4/0.0.0.0/tcp/%d\",\"/ip6/::/tcp/%d\"]", swarmPort, swarmPort))
                    .map(e -> quoteEscape ? e.replaceAll("\"", "\\\\\"") : e)  //escape quotes for windows
                    .map(e -> e.split("\\s+"))
                    .collect(Collectors.toList());
        }
    }

    private static List<MultiAddress> parseMultiAddresses(String s) {
        return Stream.of(s.split(","))
                .filter(e -> ! e.isEmpty())
                .map(MultiAddress::new)
                .collect(Collectors.toList());
    }
    public static Config buildConfig(Args args) {


        Optional<List<MultiAddress>> bootstrapNodes = args.hasArg(IPFS_BOOTSTRAP_NODES) ?
                Optional.of(parseMultiAddresses(args.getArg(IPFS_BOOTSTRAP_NODES))) :
                Optional.empty();

        int apiPort = getApiPort(args);
        int gatewayPort = args.getInt("ipfs-config-gateway-port", 8080);
        int swarmPort = args.getInt("ipfs-config-swarm-port", 4001);

        return new Config(bootstrapNodes, apiPort, gatewayPort, swarmPort);
    }

    public static int getApiPort(Args args) {
        return args.getInt("ipfs-config-api-port", 5001);
    }

    private static final String IPFS_DIR = "IPFS_PATH";
    private static final String IPFS_EXE = "ipfs-exe-path";

    private static final String DEFAULT_DIR_NAME = ".ipfs";
    private static final String DEFAULT_IPFS_EXE = "ipfs";
    private static final String DEFAULT_IPFS_TEST_EXE = "ipfs-test";

    private volatile boolean shouldBeRunning;

    /**
     * Path to IPFS binary and IPFS_DIR
     */
    public final Path ipfsPath, ipfsDir;
    public final Config config;
    public final MultiAddress proxyTarget;
    //ipfs config commands (everything after config)
    private Process process;


    public IpfsWrapper(Path ipfsPath, Path ipfsDir, Config config, MultiAddress proxytarget) {

        File ipfsDirF = ipfsDir.toFile();
        if (! ipfsDirF.isDirectory() && ! ipfsDirF.mkdirs()) {
            throw new IllegalStateException("Specified IPFS_PATH '" + ipfsDir + " is not a directory and/or could not be created");
        }

        this.ipfsPath = ipfsPath;
        this.ipfsDir = ipfsDir;
        this.config = config;
        this.proxyTarget = proxytarget;
        // add shutdown-hook to ensure ipfs daemon is killed on exit
        ALL_IPFSES.add(this);
    }

    public synchronized void configure() {
        if (! ipfsDir.resolve("config").toFile().exists()) {
            LOG().info("Initializing ipfs");
            runIpfsCmd(true, "init");
        }

        if (config.bootstrapNode.isPresent()) {
            LOG().info("Setting ipfs bootstrap nodes " + config.bootstrapNode.get());
            runIpfsCmd("bootstrap", "rm", "all");
            for (MultiAddress bootstrapNode : config.bootstrapNode.get())
                runIpfsCmd("bootstrap", "add", bootstrapNode.toString());
        }

        LOG().info("Running ipfs config");
        // Windows cmd requires and extra escape for quotes
        boolean extraQuoteEscape = System.getProperty("os.name").toLowerCase().contains("windows");
        for (String[] configCmd : config.configCmds(extraQuoteEscape)) {
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
    }

    /**
     * Wait until the ipfs id comamnd returns a sensible response.
     * <p>
     * The ipfs daemon can take up to 30 seconds to start
     * responding to requests once the daemon is started.
     */
    public void waitForDaemon(int timeoutSeconds) {
        long start = System.currentTimeMillis();
        double duration = 0;

        while (duration < timeoutSeconds) {

            // ready now?
            synchronized (this) {
                if (process !=  null && ! process.isAlive())
                    throw new IllegalStateException("ipfs daemon terminated with return code "+ process.exitValue());
            }

            if (isHttpApiListening(config.apiPort))
                return;

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

    public static boolean isHttpApiListening(int ipfsApiPort) {
        try {
            ContentAddressedStorage.HTTP api = new ContentAddressedStorage.HTTP(new JavaPoster(getLocalAddress(ipfsApiPort)), false);
            api.id().get();
            return true;
        } catch (Exception e) {}
        return false;
    }

    @Override
    public synchronized void close() {
        if (process != null && process.isAlive())
            stop();
        ensureIpfsApiFileRemoved();
    }

    public synchronized void stop() {
        if (process == null || !process.isAlive())
            throw new IllegalStateException("ipfs daemon is not running");

        LOG().info("Stopping ipfs daemon");
        shouldBeRunning = false;
        process.destroy();
        ensureIpfsApiFileRemoved();
    }

    public void startP2pProxy(MultiAddress target) {
        long sleep = 1000;
        for (int i=0; i < 6; i++) {
            try {
                runIpfsCmd(true, "p2p", "listen", "--allow-custom-protocol", "/http", target.toString());
                return;
            } catch (IllegalStateException error) {
                try {Thread.sleep(sleep);} catch (InterruptedException e) {}
                sleep *= 2;
            }
        }
    }

    /**
     * Various ipfs commands fail a high fraction of the time trying to acquire a resource lock... a  weird go/ipfs issue
     * I don't want to debug right now.
     * @param subCmd
     * @return
     */
    private Process startIpfsCmd(String... subCmd) {
        ensureReadyForCmd();
        return startIpfsCmdRetry(5, subCmd);
    }

    private Process startIpfsCmdRetry(int retryCount, String... subCmd) {
        long sleepMs = 100;
        Process process  = null;
        for (int i = 0; i < retryCount; i++) {
            process = startIpfsCmdOnce(subCmd);
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException  ie){}
            try {
                if (process.exitValue() ==  0)
                    return process;
            } catch (IllegalThreadStateException  ex)  {
                // still running
                return process;
            }
            sleepMs *= 2;
        }
        return process;
    }

    private Process startIpfsCmdOnce(String... subCmd) {
        LinkedList<String> list = new LinkedList<>(Arrays.asList(subCmd));
        list.addFirst(ipfsPath.toString());
        ProcessBuilder pb = new ProcessBuilder(list);
        pb.environment().put(IPFS_DIR, ipfsDir.toString());
        try {
            String command = Arrays.stream(subCmd).collect(Collectors.joining(" "));
            System.out.println(command);
            Process started = pb.start();
            new Thread(() -> Logging.log(started.getInputStream(),
                    "$(ipfs " + command + ") out: "), "IPFS output stream").start();
            new Thread(() -> Logging.log(started.getErrorStream(),
                    "$(ipfs " + command + ") err: "), "IPFS error stream").start();
            return started;
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

            String cmd = Stream.of(subCmd).collect(Collectors.joining(" ", "$(ipfs ", ")"));
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
            try {
                // start daemon if it isn't running
                synchronized (this) {
                    if (process == null || !process.isAlive()) {
                        start();
                        startP2pProxy(proxyTarget);
                    }
                }

                try {
                    int rc = process.waitFor();
                    if (rc != 0) {
                        LOG().warning("IPFS exited with return-code " + rc);
                        Thread.sleep(1_000);
                    }
                } catch (InterruptedException ie) {}
            } catch (Throwable t) {
                LOG().log(Level.SEVERE, t.getMessage(), t);
                try {Thread.sleep(1_000);} catch (InterruptedException e) {}
            }
        }
    }

    public static void main(String[] args) throws Exception {
        Path ipfsPath = Paths.get(
                System.getenv().getOrDefault("GOPATH", "/home/chris/go"),
                "src/github.com/ipfs/go-ipfs/cmd/ipfs/ipfs");
        Path ipfsDir = Files.createTempDirectory(null);

        Logging.init(Paths.get("log.log"), 1_000_000, 1, false, true, false);

        Config config = new Config(Optional.empty(), 5001, 8080, 4001);
        Args a = Args.parse(args);
        MultiAddress proxytarget = new MultiAddress(a.getArg("proxy-target"));
        IpfsWrapper ipfsWrapper = new IpfsWrapper(ipfsPath, ipfsDir, config, proxytarget);

        ipfsWrapper.configure();

        new Thread(ipfsWrapper, "IPFS wrapper").start();
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
                args.fromPeergosDir(IPFS_DIR, DEFAULT_DIR_NAME);

        //ipfs exe defaults to $PEERGOS_PATH/ipfs
        Path ipfsExe = getIpfsExePath(args);

        LOG().info("Using IPFS dir " + ipfsDir + " and IPFS binary " + ipfsExe);
        Config config = buildConfig(args);
        return new IpfsWrapper(ipfsExe, ipfsDir, config, new MultiAddress(args.getArg("proxy-target")));
    }

    /**
     * Build an IpfsWrapper based on args.
     * <p>
     * Start running it in a sub-process.
     *
     * Block until the ipfs-daemon is ready for requests.
     *
     * Restart the daemon if it dies.
     *
     * @param args
     * @return
     */
    public static IpfsWrapper launch(Args args) {
        IpfsWrapper ipfs = IpfsWrapper.build(args);
        return launchAndManage(ipfs);
    }

    public static IpfsWrapper launchAndManage(IpfsWrapper ipfs) {

        ipfs.configure();

        new Thread(ipfs).start();

        ipfs.waitForDaemon(30);

        return ipfs;
    }

    public static IpfsWrapper launchOnce(IpfsWrapper ipfs) {

        ipfs.configure();

        ipfs.start();

        ipfs.waitForDaemon(30);

        return ipfs;
    }

    public static Path getIpfsExePath(Args args) {
        return IpfsInstaller.getExecutableForOS(args.hasArg(IPFS_EXE) ?
                Paths.get(args.getArg(IPFS_EXE)) :
                args.fromPeergosDir("ipfs-exe", DEFAULT_IPFS_EXE));
    }


    public static Path getIpfsTestExePath(Args args) {
        return args.hasArg(IPFS_EXE) ?
                Paths.get(args.getArg(IPFS_EXE)) :
                args.fromPeergosDir("ipfs-exe", DEFAULT_IPFS_TEST_EXE);
    }

    /**
     *
     */
    private synchronized void ensureReadyForCmd() {

        if (process != null && process.isAlive())
            // we are running the daemon process
            return;

        // we're not running the daemon, ensure the API file is removed.
        ensureIpfsApiFileRemoved();
    }

    private void ensureIpfsApiFileRemoved() {
        Path apiPath = ipfsDir.resolve("api");
        try {
            Files.deleteIfExists(apiPath);
        } catch (IOException ioe) {
            throw new IllegalStateException(ioe.getMessage(), ioe);
        }
    }

    private static List<IpfsWrapper> ALL_IPFSES = new ArrayList<>();

    public static List<IpfsWrapper> ipfsRegistry()  {
        return new ArrayList<>(ALL_IPFSES);
    }

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            for (IpfsWrapper ipfs : ALL_IPFSES) {
                ipfs.close();
            }
        }, "IPFS shutdown"));
    }
}
