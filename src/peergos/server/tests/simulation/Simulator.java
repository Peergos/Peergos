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
import peergos.shared.user.fs.cryptree.CryptreeNode;
import peergos.shared.util.Pair;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static peergos.server.tests.UserTests.buildArgs;

/**
 * Run some I/O and then check the file-system is as expected.
 */
public class Simulator implements Runnable {
    private class SimulationRecord {
        private final List<Simulation> simuations =  new ArrayList<>();
        private final List<Long> timestamps =  new ArrayList<>();
        public void add(Simulation simulation) {
            long now = System.currentTimeMillis();
            timestamps.add(now);
            simuations.add(simulation);
        }
    }

    private static final Logger LOG = Logging.LOG();
    private static final int MIN_FILE_LENGTH = 256;
    private static final int MAX_FILE_LENGTH = Integer.MAX_VALUE;

    enum Simulation {READ, WRITE, MKDIR, RM}

    private final int opCount;
    private final Random random;
    private final Supplier<Simulation> getNextSimulation;
    private final long meanFileLength;
    private final SimulationRecord simulationRecord = new SimulationRecord();

    private final FileSystem referenceFileSystem, testFileSystem;

    private final Map<Path, List<String>> simulatedDirectoryToFiles = new HashMap<>();

    long fileNameCounter;

    private String getNextName() {
        return "" + fileNameCounter++;
    }


    private Path getRandomExistingFile() {

        Path dir = getRandomExistingDirectory();
        List<String> fileNames = simulatedDirectoryToFiles.get(dir);
        if (fileNames.isEmpty())
            return getRandomExistingFile();
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

    private void rm() {
        Path path = getRandomExistingFile();
        simulatedDirectoryToFiles.get(path.getParent()).remove(path.getFileName().toString());

        testFileSystem.delete(path);
        referenceFileSystem.delete(path);
    }

    private Path mkdir() {
        String dirBaseName = getNextName();

        Path path = getRandomExistingDirectory().resolve(dirBaseName);
        simulatedDirectoryToFiles.putIfAbsent(path, new ArrayList<>());
        LOG.info("mkdir-ing  "+ path);
        testFileSystem.mkdir(path);
        referenceFileSystem.mkdir(path);
        return path;
    }

    private Path getAvailableFilePath() {
        return getRandomExistingDirectory().resolve(getNextName());
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

    public Simulator(int opCount, Random  random, Supplier<Simulation> getNextSimulation, long meanFileLength, FileSystem referenceFileSystem, FileSystem testFileSystem) {
        if (! referenceFileSystem.user().equals(testFileSystem.user()))
            throw new IllegalStateException("Users must be the same");

        simulatedDirectoryToFiles.put(Paths.get("/"+ referenceFileSystem.user()), new ArrayList<>());

        this.opCount = opCount;
        this.random = random;
        this.getNextSimulation = getNextSimulation;
        this.meanFileLength = meanFileLength;
        this.referenceFileSystem = referenceFileSystem;
        this.testFileSystem = testFileSystem;
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

    private void init() {
        //seed file-system with a directory and a file
        run(Simulation.MKDIR);
        run(Simulation.WRITE);
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
            case RM:
                rm();
                break;
            default:
                throw new IllegalStateException("Unexpected simulation " + simulation);
        }
        simulationRecord.add(simulation);
    }

    public void run() {
        LOG.info("Running file-system IO-simulation");

        init();

        for (int iOp = 2; iOp < opCount; iOp++) {
            Simulation simulation = getNextSimulation.get();
            run(simulation);
        }

        LOG.info("Running file-system verification");
        boolean isVerified = verify();
        LOG.info("System verified =  "+ isVerified);
    }

    private boolean verify() {
        Set<Path> expectedFiles = simulatedDirectoryToFiles.entrySet().stream()
                .flatMap(e -> e.getValue().stream()
                        .map(file -> e.getKey().resolve(file)))
                .collect(Collectors.toSet());



        boolean isVerified = true;

        for (FileSystem fs : Arrays.asList(testFileSystem, referenceFileSystem)) {

            Set<Path> paths = new HashSet<>();
            fs.walk(paths::add);

            Set<Path> expectedFilesAndFolders  =  new HashSet<>(expectedFiles);
            expectedFilesAndFolders.addAll(simulatedDirectoryToFiles.keySet());

            // extras?
            Set<Path> extras = new HashSet<>(paths);
            extras.removeAll(expectedFilesAndFolders);

            for (Path extra : extras) {
                LOG.info("filesystem " + fs + " has an extra path " + extra);
                isVerified = false;
            }

            // missing?
            expectedFilesAndFolders.removeAll(paths);
            for (Path missing : expectedFilesAndFolders) {
                LOG.info("filesystem " + fs + " is missing  the path " + missing);
                isVerified = false;
            }
        }

        // contents
        for (Path path : expectedFiles) {
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
                .with("peergos.password", "testpassword")
                .with("pki.keygen.password", "testpkipassword")
                .with("pki.keyfile.password", "testpassword")
                .with(IpfsWrapper.IPFS_BOOTSTRAP_NODES, ""); // no bootstrapping
        Main.PKI_INIT.main(args);

        NetworkAccess networkAccess = NetworkAccess.buildJava(new URL("http://localhost:" + args.getInt("port"))).get();


        LOG.info("***NETWORK READY***");


        UserContext userContext = PeergosNetworkUtils.ensureSignedUp("some-user", "some password", networkAccess, crypto);

        PeergosFileSystemImpl peergosFileSystem = new PeergosFileSystemImpl(userContext);

        Path root = Files.createTempDirectory("test_filesystem");
        NativeFileSystemImpl nativeFileSystem = new NativeFileSystemImpl(root, "some-user");

        int opCount = 100;

        Map<Simulation, Double> probabilities = Stream.of(
                new Pair<>(Simulation.READ, 0.05),
                new Pair<>(Simulation.WRITE, 0.75),
                new Pair<>(Simulation.RM, 0.15),
                new Pair<>(Simulation.MKDIR, 0.05)
        ).collect(
                Collectors.toMap(e -> e.left, e-> e.right));

        final Random random = new Random(1);

        Supplier<Simulation> getNextSimulation = () -> {
            double acc = 0;
            double p = random.nextDouble();
            for (Map.Entry<Simulation, Double> e : probabilities.entrySet()) {
                Simulation simulation = e.getKey();
                Double prob = e.getValue();
                acc += prob;
                if (p < acc)
                    return simulation;
            }
            throw new IllegalStateException();
        };

        //hard-mode
        CryptreeNode.setMaxChildLinkPerBlob(10);

        int meanFileLength = 256;
        Simulator simulator = new Simulator(opCount, random, getNextSimulation, meanFileLength, nativeFileSystem, peergosFileSystem);

        try {
            simulator.run();
        } catch (Throwable t) {
            LOG.log(Level.SEVERE, t, () -> "So long");
        } finally {
            System.exit(0);
        }

    }
}
