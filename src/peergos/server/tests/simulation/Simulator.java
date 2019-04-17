package peergos.server.tests.simulation;

import peergos.server.Main;
import peergos.server.storage.IpfsWrapper;
import peergos.server.tests.UserTests;
import peergos.server.util.Args;
import peergos.server.util.Logging;
import peergos.server.util.PeergosNetworkUtils;
import peergos.shared.Crypto;
import peergos.shared.NetworkAccess;
import peergos.shared.user.UserContext;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static peergos.server.tests.UserTests.buildArgs;

/**
 * Run some I/O and then check the file-system is as expected.
 */
public class Simulator implements Runnable {
    private static final Logger LOG = Logging.LOG();
    private static final int MIN_FILE_LENGTH = 256;
    private static final int MAX_FILE_LENGTH = Integer.MAX_VALUE;

    enum Simulation {READ, WRITE, MKDIR}

    private final int opCount, seed;
    private final Random random;
    private final double readProbability; // fraction of read ops
    private final double mkdirProbability; // fraction of mkdir ops
    private final double writeProbability; // fraction of mkdir ops
    private final long meanFileLength;

    private final FileSystem referenceFileSystem, testFileSystem;

    private final Map<Path, List<String>> simulatedDirectoryToFiles = new HashMap<>();

    long fileNameCounter;
    long directoryNameCounter;

    private String getNextName() {
        return "" + fileNameCounter++;
    }


    private Path getRandomExistingFile() {

        Path dir = getRandomExistingDirectory();
        List<String> fileNames = simulatedDirectoryToFiles.get(dir);
        int pos = random.nextInt(fileNames.size());
        String fileName = fileNames.get(pos);
        return dir.resolve(fileName);
    }

    private Path getRandomExistingDirectory() {
        List<Path> directories = new ArrayList<>(simulatedDirectoryToFiles.keySet());

        try {
            int pos = random.nextInt(directories.size());
            return directories.get(pos);
        } catch (Exception ex) {
            System.out.println();
            throw ex;
        }

    }

    private Path mkdir() {
        String dirBaseName = getNextName();

        Path path = getRandomExistingDirectory().resolve(dirBaseName);
        simulatedDirectoryToFiles.putIfAbsent(path, new ArrayList<>());
        return path;
    }

    private Path getAvailableFilePath() {
        return getRandomExistingDirectory().resolve(getNextName());
    }

    private Simulation getNextSimulation() {
        double d = random.nextDouble();
        if (d < readProbability)
            return Simulation.READ;
        if (d < readProbability + writeProbability)
            return Simulation.WRITE;
        return Simulation.MKDIR;
    }

    private int getNextFileLength() {
        double pos = random.nextGaussian();
        int targetLength = (int) (pos * meanFileLength);
        return Math.min(MAX_FILE_LENGTH,
                Math.max(targetLength, MIN_FILE_LENGTH));
    }

    private byte[] getNextFileContents() {
        byte[] bytes = new byte[getNextFileLength()];
        random.nextBytes(bytes);
        return bytes;
    }

    public Simulator(int opCount, int seed, double readProbability, double mkdirProbability, double writeProbability, long meanFileLength, FileSystem referenceFileSystem, FileSystem testFileSystem) {
        if (readProbability + mkdirProbability > 1.0)
            throw new IllegalStateException();

        if (! referenceFileSystem.user().equals(testFileSystem.user()))
            throw new IllegalStateException("Users must be the same");

        simulatedDirectoryToFiles.put(Paths.get("/"+ referenceFileSystem.user()), new ArrayList<>());

        this.opCount = opCount;
        this.seed = seed;
        this.readProbability = readProbability;
        this.mkdirProbability = mkdirProbability;
        this.writeProbability = writeProbability;
        this.meanFileLength = meanFileLength;
        this.referenceFileSystem = referenceFileSystem;
        this.testFileSystem = testFileSystem;
        this.random = new Random(seed);
    }

    private boolean read(Path path) {
        LOG.info("Reading path " + path);
        byte[] refBytes = referenceFileSystem.read(path);
        byte[] testBytes = testFileSystem.read(path);
        return Arrays.equals(refBytes, testBytes);
    }

    private void write() {
        Path path = getAvailableFilePath();
        byte[] fileContents = getNextFileContents();

        Path dirName = path.getParent();
        String fileName = path.getFileName().toString();
        simulatedDirectoryToFiles.get(dirName).add(fileName);

        LOG.info("Writing path " + path.resolve(fileName) + " with length " + fileContents.length);

        testFileSystem.write(path, fileContents);
        referenceFileSystem.write(path, fileContents);
    }


    private void run(Simulation simulation) {
        switch (simulation) {
            case READ:
                read(getRandomExistingFile());
                break;
            case WRITE:
                write();
                break;
            case MKDIR:
                mkdir();
                break;
            default:
                throw new IllegalStateException("Unexpected simulation " + simulation);
        }
    }

    public void run() {
        LOG.info("Running file-system IO-simulation");

        //seed file-system
        run(Simulation.MKDIR);
        run(Simulation.WRITE);
        for (int iOp = 0; iOp < opCount; iOp++) {
            Simulation simulation = getNextSimulation();
            run(simulation);
        }

        LOG.info("Running file-system verification");
        verify();
    }

    private boolean verify() {
        Set<Path> expectedPaths = simulatedDirectoryToFiles.entrySet().stream()
                .flatMap(e -> e.getValue().stream()
                        .map(file -> e.getKey().resolve(file)))
                .collect(Collectors.toSet());

        Set<Path> paths = new HashSet<>();
        boolean isVerified = true;

        for (FileSystem fs : Arrays.asList(testFileSystem, referenceFileSystem)) {

            fs.walk(file -> paths.add(file));

            // extras?
            Set<Path> extras = new HashSet<>(expectedPaths);
            paths.removeAll(extras);

            for (Path extra : extras) {
                LOG.info("filesystem " + fs + " has an extra path " + extra);
                isVerified = false;
            }

            // missing?
            paths.removeAll(expectedPaths);
            for (Path missing : paths) {
                LOG.info("filesystem " + fs + " is missing  the path " + missing);
                isVerified = false;
            }
        }

        // contents
        for (Path path : expectedPaths) {
            try {
                byte[] testData = testFileSystem.read(path);
                byte[] refData = referenceFileSystem.read(path);
                if (!Arrays.equals(testData, refData)) {
                    LOG.info("Path " + path + " has different contents between the file-systems");
                    isVerified = false;
                }
            } catch (Exception ex) {
                LOG.info("Failed to read path + " + path);
                isVerified = false;
            }
        }
        return isVerified;
    }

    public static void main(String[] a) throws Exception {
        Crypto crypto = Crypto.initJava();
        Args args = buildArgs()
                .with("useIPFS", "true")
                .with(IpfsWrapper.IPFS_BOOTSTRAP_NODES, ""); // no bootstrapping
        Main.PKI.main(args);

        NetworkAccess networkAccess = NetworkAccess.buildJava(new URL("http://localhost:" + args.getInt("port"))).get();


        LOG.info("***NETWORK READ***");


        UserContext userContext = PeergosNetworkUtils.ensureSignedUp("some-user", "some password", networkAccess, crypto);

        PeergosFileSystemImpl peergosFileSystem = new PeergosFileSystemImpl(userContext);

        Path root = Files.createTempDirectory("test_filesystem");
        NativeFileSystemImpl nativeFileSystem = new NativeFileSystemImpl(root, "some-user");

        Simulator simulator = new Simulator(1, 1, 0.1, 0.1, 0.8, 100, nativeFileSystem, peergosFileSystem);
        try {
            simulator.run();
        } catch (Throwable t) {
            LOG.log(Level.SEVERE, t, () -> "so long");
        } finally {
            System.exit(0);
        }

    }
}
