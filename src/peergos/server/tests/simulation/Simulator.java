package peergos.server.tests.simulation;

import peergos.server.Main;
import peergos.server.simulation.AccessControl;
import peergos.server.simulation.FileSystem;
import peergos.server.simulation.PeergosFileSystemImpl;
import peergos.server.storage.IpfsWrapper;
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
        private final List<Simulation> simuations = new ArrayList<>();
        private final List<Long> timestamps = new ArrayList<>();

        public void add(Simulation simulation) {
            long now = System.currentTimeMillis();
            timestamps.add(now);
            simuations.add(simulation);
        }
    }

    public static class FileSystemIndex {
        //user -> [dir -> file-name]
        private final Random random;
        private final Map<String, Map<Path, List<String>>> index = new HashMap<>();

        public FileSystemIndex(Random random) {
            this.random = random;
        }

        private Path getRandomExistingDirectory(String user,  boolean skipRoot) {
            List<Path> dirs = new ArrayList<>(index.get(user).keySet());
            //skip the root folder - it is special
            if (skipRoot)
                dirs.remove(Paths.get("/"+user));
            int pos = random.nextInt(dirs.size());
            return dirs.get(pos);
        }

        private Path getRandomExistingFile(String user) {

            Path dir = getRandomExistingDirectory(user, false);
            List<String> fileNames = index.get(user).get(dir);

            if (fileNames.isEmpty())
                return getRandomExistingFile(user);
            int pos = random.nextInt(fileNames.size());
            String fileName = fileNames.get(pos);
            return dir.resolve(fileName);
        }

        public void addUser(String user) {
            index.put(user, new HashMap<>());
        }

        public Map<Path, List<String>> getDirToFiles(String user) {
            return index.get(user);
        }
    }

    private static final Logger LOG = Logging.LOG();
    private static final int MIN_FILE_LENGTH = 256;
    private static final int MAX_FILE_LENGTH = Integer.MAX_VALUE;

    enum Simulation {READ, WRITE, MKDIR, RM, RMDIR,
        GRANT_READ_FILE, GRANT_READ_DIR, GRANT_WRITE_FILE, GRANT_WRITE_DIR,
        REVOKE_READ, REVOKE_WRITE;

        public FileSystem.Permission permission() {
            switch (this) {
                case GRANT_READ_FILE:
                case GRANT_READ_DIR:
                case REVOKE_READ:
                    return FileSystem.Permission.READ;

                case GRANT_WRITE_DIR:
                case GRANT_WRITE_FILE:
                case REVOKE_WRITE:
                    return FileSystem.Permission.WRITE;
            }
            return null;
        }
    }

    private final int opCount;
    private final Random random;
    private final Supplier<Simulation> getNextSimulation;
    private final long meanFileLength;
    private final SimulationRecord simulationRecord = new SimulationRecord();
    private final FileSystems fileSystems;
    private final FileSystemIndex index;


    long fileNameCounter;

    private String getNextName() {
        return "" + fileNameCounter++;
    }

    private void rm(String user) {
        Path path = index.getRandomExistingFile(user);
        index.getDirToFiles(user).get(path.getParent()).remove(path.getFileName().toString());
        FileSystem testFileSystem = fileSystems.getTestFileSystem(user);
        FileSystem referenceFileSystem = fileSystems.getReferenceFileSystem(user);

        testFileSystem.delete(path);
        referenceFileSystem.delete(path);
    }

    private void rmdir(String user) {
        Path path = index.getRandomExistingDirectory(user, false);
        //rm -r
        //TODO
        Map<Path, List<String>> dirsToFiles = index.getDirToFiles(user);
        for (Path p : new ArrayList<>(dirsToFiles.keySet())) {
            if (p.startsWith(path))
                dirsToFiles.remove(p);
        }
        FileSystem testFileSystem = fileSystems.getTestFileSystem(user);
        FileSystem referenceFileSystem = fileSystems.getReferenceFileSystem(user);

        testFileSystem.delete(path);
        referenceFileSystem.delete(path);
    }

    private Path mkdir(String user) {
        String dirBaseName = getNextName();

        Path path = index.getRandomExistingDirectory(user, false).resolve(dirBaseName);
        index.getDirToFiles(user).putIfAbsent(path, new ArrayList<>());
        LOG.info("mkdir-ing  " + path);
        FileSystem testFileSystem = fileSystems.getTestFileSystem(user);
        FileSystem referenceFileSystem = fileSystems.getReferenceFileSystem(user);
        testFileSystem.mkdir(path);
        referenceFileSystem.mkdir(path);
        return path;
    }

    private Path getAvailableFilePath(String user) {
        return index.getRandomExistingDirectory(user, false).resolve(getNextName());
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


    private static class FileSystems {
        private final List<Pair<FileSystem, FileSystem>> peergosAndNativeFileSystemPair;
        private final Random random;

        public FileSystems(List<Pair<FileSystem, FileSystem>> peergosAndNativeFileSystemPair, Random random) {
            for (Pair<FileSystem, FileSystem> userFileSystem : peergosAndNativeFileSystemPair) {
                boolean usersMatch = userFileSystem.left.user().equals(userFileSystem.right.user());
                if (!usersMatch)
                    throw new IllegalStateException();
            }
            this.peergosAndNativeFileSystemPair = peergosAndNativeFileSystemPair;
            this.random = random;
        }

        /**
         * @return (peergosFileSystem, referenceFileSystem)
         */

        public String getNextUser() {
            int pos = random.nextInt(peergosAndNativeFileSystemPair.size());
            return peergosAndNativeFileSystemPair.get(pos).right.user();
        }

        public String getNextUser(String notThisUser) {
            do {
                int pos = random.nextInt(peergosAndNativeFileSystemPair.size());
                String user = peergosAndNativeFileSystemPair.get(pos).right.user();
                if (user.equals(notThisUser))
                    continue;
                return user;
            } while (true);
        }

        public NativeFileSystemImpl getReferenceFileSystem(String user) {
            return peergosAndNativeFileSystemPair.stream()
                    .filter(e -> e.right.user().equals(user))
                    .map(e -> (NativeFileSystemImpl) e.right)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException());
        }

        public PeergosFileSystemImpl getTestFileSystem(String user) {
            return peergosAndNativeFileSystemPair.stream()
                    .filter(e -> e.right.user().equals(user))
                    .map(e -> (PeergosFileSystemImpl) e.left)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException());
        }

        public List<String> getUsers() {
            return peergosAndNativeFileSystemPair.stream()
                    .map(e -> e.left.user())
                    .collect(Collectors.toList());
        }
    }

    public Simulator(int opCount, Random random, long meanFileLength,
                     Supplier<Simulation> getNextSimulation,
                     FileSystems fileSystems) {

        this.fileSystems = fileSystems;
        this.opCount = opCount;
        this.random = random;
        this.getNextSimulation = getNextSimulation;
        this.meanFileLength = meanFileLength;
        this.index = new FileSystemIndex(random);
    }

    private boolean read(String user, Path path) {
        LOG.info("Reading path " + path);

        FileSystem testFileSystem = fileSystems.getTestFileSystem(user);
        FileSystem referenceFileSystem = fileSystems.getReferenceFileSystem(user);

        byte[] refBytes = referenceFileSystem.read(path);
        byte[] testBytes = testFileSystem.read(path);
        return Arrays.equals(refBytes, testBytes);
    }

    private void write(String user) {
        Path path = getAvailableFilePath(user);
        byte[] fileContents = getNextFileContents();

        Path dirName = path.getParent();
        String fileName = path.getFileName().toString();
        Map<Path, List<String>> dirsToFiles = index.getDirToFiles(user);
        dirsToFiles.get(dirName).add(fileName);

        LOG.info("Writing path " + path.resolve(fileName) + " with length " + fileContents.length);

        FileSystem testFileSystem = fileSystems.getTestFileSystem(user);
        FileSystem referenceFileSystem = fileSystems.getReferenceFileSystem(user);

        testFileSystem.write(path, fileContents);
        referenceFileSystem.write(path, fileContents);
    }

    private boolean grantPermission(String granter, String grantee, Path path, FileSystem.Permission permission) {
        LOG.info("Granting "+ permission.name()+"-access to " + path + " for granter " + granter + " and grantee " + grantee);

        FileSystem testFileSystem = fileSystems.getTestFileSystem(granter);
        FileSystem referenceFileSystem = fileSystems.getReferenceFileSystem(granter);

        testFileSystem.grant(path, grantee, permission);
        referenceFileSystem.grant(path, grantee, permission);

        return true;
    }

    private boolean revokePermission(String revoker, String revokee, Path path, FileSystem.Permission permission) {
        LOG.info("Revoking "+permission.name()+"-access to " + path + "for revoker " + revoker + " and revokee " + revokee);

        FileSystem testFileSystem = fileSystems.getTestFileSystem(revoker);
        FileSystem referenceFileSystem = fileSystems.getReferenceFileSystem(revoker);

        testFileSystem.revoke(path, revokee, permission);
        referenceFileSystem.revoke(path, revokee, permission);

        return true;
    }

    private void init() {
        List<String> users = this.fileSystems.getUsers();
        Collections.sort(users);

        for (String user : users) {
            index.addUser(user);
            index.getDirToFiles(user).put(Paths.get("/" + user), new ArrayList<>());
            // seed the file-system
            run(Simulation.MKDIR, user);
            run(Simulation.WRITE, user);

            // ALL PAIRWISE FRIENDS
            for (String otherUser : users) {
                if (user.compareTo(otherUser) >= 0)
                    continue;
                fileSystems.getTestFileSystem(user).follow(fileSystems.getTestFileSystem(otherUser));
                fileSystems.getReferenceFileSystem(user).follow(fileSystems.getReferenceFileSystem(otherUser));
            }

        }
    }

    private void run(Simulation simulation) {
        String nextUser = fileSystems.getNextUser();
        run(simulation, nextUser);
    }

    private void run(Simulation simulation, String user) {
        Supplier<String> otherUser = () -> fileSystems.getNextUser(user);
        Supplier<Path> randomFilePathForUser = () -> index.getRandomExistingFile(user);
        Supplier<Path> randomFolderPathForUser = () -> index.getRandomExistingDirectory(user, true);

        switch (simulation) {
            case READ:
                read(user, randomFilePathForUser.get());
                break;
            case WRITE:
                write(user);
                break;
            case MKDIR:
                mkdir(user);
                break;
            case RM:
                rm(user);
                break;
            case RMDIR:
                rmdir(user);
                break;
            case GRANT_READ_FILE:
            case GRANT_WRITE_FILE:
                grantPermission(user, otherUser.get(), randomFilePathForUser.get(), simulation.permission());
            case GRANT_READ_DIR:
            case GRANT_WRITE_DIR:
                grantPermission(user, otherUser.get(), randomFolderPathForUser.get(), simulation.permission());
                break;
            case REVOKE_READ:
            case REVOKE_WRITE:
                revokePermission(user, otherUser.get(),
                        fileSystems.getReferenceFileSystem(user).getRandomSharedPath(random, simulation.permission()), simulation.permission());
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
        LOG.info("System verified =  " + isVerified);
    }

    /**
     * This  will  overwrite the content @ path with [0].
     * @param user
     * @param path
     * @return
     */
    private boolean verifySharingPermissions(String user, Path path) {
        FileSystem peergosFileSystem = fileSystems.getTestFileSystem(user);
        FileSystem nativeFileSystem = fileSystems.getReferenceFileSystem(user);

        boolean isVerified = true;
        for (FileSystem.Permission permission : FileSystem.Permission.values()) {
            Set<String> shareesInPeergos = new HashSet<>(peergosFileSystem.getSharees(path, permission));
            Set<String> shareesInLocal = new HashSet<>(nativeFileSystem.getSharees(path, permission));
            if (! shareesInPeergos.equals(shareesInLocal)) {
                LOG.info("User " + peergosFileSystem.user() + " path " + path + " has peergos-fs " + permission.name() + "ers " +
                        shareesInPeergos + " and local-fs " + permission.name() + "ers " + shareesInLocal);
                isVerified = false;
            }

            //check they can actually read
            for (String sharee : shareesInPeergos) {

                byte[] read = null;
                PeergosFileSystemImpl fs = fileSystems.getTestFileSystem(sharee);
                switch (permission) {
                    case READ:
                        try {
                            //can read?
                            read = fs.read(path);
                        } catch (Exception ex) {
                            LOG.log(Level.WARNING, "User "+ sharee +" could not read shared-path "+ path +"!", ex);
                            isVerified = false;
                        }
                    case WRITE:
                        try {
                            // can overwrite?
                            fs.write(path, new byte[]{0});
                        } catch (Exception ex) {
                            LOG.log(Level.WARNING, "User "+ sharee +" could not write  shared-path "+ path +"!", ex);
                        }
                    default:
                        throw new IllegalStateException();
                }
            }


        }
        return isVerified;
    }

    private boolean verifyContents(String user, Path path) {
        FileSystem peergosFileSystem = fileSystems.getTestFileSystem(user);
        FileSystem nativeFileSystem = fileSystems.getReferenceFileSystem(user);

        try {
            byte[] testData = peergosFileSystem.read(path);
            byte[] refData = nativeFileSystem.read(path);
            if (!Arrays.equals(testData, refData)) {
                LOG.info("Path " + path + " has different contents between the file-systems");
                return false;
            }
        } catch (Exception ex) {
            LOG.info("Failed to read path + " + path);
            return false;
        }
        return true;
    }

    private boolean verify() {

        boolean isGlobalVerified = true;

        for (String user : fileSystems.getUsers()) {

            Map<Path, List<String>> dirToFiles = index.getDirToFiles(user);
            Set<Path> expectedFilesForUser = dirToFiles.entrySet().stream()
                    .flatMap(ee -> ee.getValue().stream()
                            .map(file -> ee.getKey().resolve(file)))
                    .collect(Collectors.toSet());


            FileSystem testFileSystem = fileSystems.getTestFileSystem(user);
            FileSystem referenceFileSystem = fileSystems.getReferenceFileSystem(user);

            boolean isUserVerified = true;
            for (FileSystem fs : Arrays.asList(testFileSystem, referenceFileSystem)) {

                Set<Path> paths = new HashSet<>();
                fs.walk(paths::add);

                Set<Path> expectedFilesAndFolders = new HashSet<>(expectedFilesForUser);
                expectedFilesAndFolders.addAll(dirToFiles.keySet());

                // extras?
                Set<Path> extras = new HashSet<>(paths);
                extras.removeAll(expectedFilesAndFolders);

                for (Path extra : extras) {
                    LOG.info("filesystem " + fs + " has an extra path " + extra);
                    isUserVerified = false;
                }

                // missing?
                expectedFilesAndFolders.removeAll(paths);
                for (Path missing : expectedFilesAndFolders) {
                    LOG.info("filesystem " + fs + " is missing  the path " + missing);
                    isUserVerified = false;
                }
            }

            // contents
            for (Path path : expectedFilesForUser) {
                boolean verifyContents = verifyContents(user, path);
                boolean sharingPermissionsAreVerified = verifySharingPermissions(user, path);
                isUserVerified &= verifyContents;
                isUserVerified &= sharingPermissionsAreVerified;
            }
            if (!isUserVerified) {
                LOG.info("User " + user + " is not verified!");
                isGlobalVerified = false;
            }


        }
        return isGlobalVerified;
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
        LOG.info("***NETWORK READY***");

        AccessControl accessControl = new AccessControl.MemoryImpl();
        Function<String, Pair<FileSystem, FileSystem>> fsPairBuilder = username -> {
            try {
                NetworkAccess networkAccess = NetworkAccess.buildJava(new URL("http://localhost:" + args.getInt("port"))).get();
                UserContext userContext = PeergosNetworkUtils.ensureSignedUp(username, username + "_password", networkAccess, crypto);
                PeergosFileSystemImpl peergosFileSystem = new PeergosFileSystemImpl(userContext);
                Path root = Files.createTempDirectory("test_filesystem-" + username);
                NativeFileSystemImpl nativeFileSystem = new NativeFileSystemImpl(root, username, accessControl);
                return new Pair<>(peergosFileSystem, nativeFileSystem);
            } catch (Exception ioe) {
                throw new IllegalStateException(ioe);
            }
        };


        int opCount = 200;

        Map<Simulation, Double> probabilities = Stream.of(
                new Pair<>(Simulation.READ, 0.0),
                new Pair<>(Simulation.WRITE, 0.4),
                new Pair<>(Simulation.RM, 0.0),
                new Pair<>(Simulation.MKDIR, 0.1),
                new Pair<>(Simulation.RMDIR, 0.0),
                new Pair<>(Simulation.GRANT_READ_FILE, 0.2),
                new Pair<>(Simulation.GRANT_WRITE_FILE, 0.1),
                new Pair<>(Simulation.GRANT_READ_DIR, 0.05),
                new Pair<>(Simulation.GRANT_WRITE_DIR, 0.05),
                new Pair<>(Simulation.REVOKE_READ, 0.05),
                new Pair<>(Simulation.REVOKE_WRITE, 0.05)
        ).collect(
                Collectors.toMap(e -> e.left, e -> e.right));

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
        List<Pair<FileSystem, FileSystem>> fs = Arrays.asList(
                fsPairBuilder.apply("left"),
                fsPairBuilder.apply("right")
        );


        FileSystems fileSystems = new FileSystems(fs, random);
        Simulator simulator = new Simulator(opCount, random, meanFileLength, getNextSimulation, fileSystems);

        try {
            simulator.run();
        } catch (Throwable t) {
            LOG.log(Level.SEVERE, t, () -> "So long");
        } finally {
            System.exit(0);
        }

    }
}
