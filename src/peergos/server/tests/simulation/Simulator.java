package peergos.server.tests.simulation;

import peergos.server.*;
import peergos.server.simulation.AccessControl;
import peergos.server.simulation.FileSystem;
import peergos.server.simulation.PeergosFileSystemImpl;
import peergos.server.simulation.Stat;
import peergos.server.storage.IpfsWrapper;
import peergos.server.util.Args;
import peergos.server.util.Logging;
import peergos.server.tests.PeergosNetworkUtils;
import peergos.shared.Crypto;
import peergos.shared.NetworkAccess;
import peergos.shared.user.*;
import peergos.shared.user.fs.cryptree.CryptreeNode;
import peergos.shared.util.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static peergos.server.tests.UserTests.buildArgs;

/**
 * Run some I/O and then check the file-system is as expected.
 */
public class Simulator {
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
        private final Map<String, Map<Path, Set<String>>> index = new HashMap<>();

        public FileSystemIndex(Random random) {
            this.random = random;
        }

        private Path getRandomExistingDirectory(String user,  boolean skipRoot) {
            List<Path> dirs = new ArrayList<>(index.get(user).keySet());
            Collections.sort(dirs);
            //skip the root folder - it is special
            if (skipRoot)
                dirs.remove(PathUtil.get("/"+user));
            int pos = random.nextInt(dirs.size());
            return dirs.get(pos);
        }

        private Path getRandomExistingFile(String user) {

            Path dir = getRandomExistingDirectory(user, false);
            List<String> fileNames = new ArrayList<>(index.get(user).get(dir));

            if (fileNames.isEmpty())
                return getRandomExistingFile(user);
            int pos = random.nextInt(fileNames.size());
            String fileName = fileNames.get(pos);
            return dir.resolve(fileName);
        }

        public void addUser(String user) {
            index.put(user, new HashMap<>());
        }

        public Map<Path, Set<String>> getDirToFiles(String user) {
            return index.get(user);
        }
    }

    private static final Logger LOG = Logging.LOG();
    private static final int MIN_FILE_LENGTH = 256;
    private static final int MAX_FILE_LENGTH = Integer.MAX_VALUE;

    enum Simulation {
        READ_OWN_FILE,
        WRITE_OWN_FILE,
        READ_SHARED_FILE,
        READ_SHARED_DIRECTORY,
        WRITE_SHARED_FILE,
        WRITE_SHARED_DIRECTORY,
        MKDIR,
        RM,
        RMDIR,
        GRANT_READ_FILE,
        GRANT_READ_DIR,
        GRANT_WRITE_FILE,
        GRANT_WRITE_DIR,
        REVOKE_READ,
        REVOKE_WRITE;

        public FileSystem.Permission permission() {
            switch (this) {
                case GRANT_READ_FILE:
                case GRANT_READ_DIR:
                case REVOKE_READ:
                case READ_SHARED_FILE:
                case READ_SHARED_DIRECTORY:
                    return FileSystem.Permission.READ;

                case GRANT_WRITE_DIR:
                case GRANT_WRITE_FILE:
                case REVOKE_WRITE:
                case WRITE_SHARED_FILE:
                case WRITE_SHARED_DIRECTORY:
                    return FileSystem.Permission.WRITE;
            }
            return null;
        }
    }

    private final int opCount;
    private final Random random;
    private final Supplier<Simulation> getNextSimulation;
    private final long meanFileLength;
    private final boolean randomizeFriendNetwork;
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
        log(user, Simulation.RM, path);
        testFileSystem.delete(path);
        referenceFileSystem.delete(path);
    }

    private void rmdir(String user) {
        Path path = index.getRandomExistingDirectory(user, false);
        log(user, Simulation.RMDIR, path);

        Map<Path, Set<String>> dirsToFiles = index.getDirToFiles(user);
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
        return mkdir(user, path);
    }

    private Path mkdir(String user, Path path) {
        index.getDirToFiles(user).putIfAbsent(path, new HashSet<>());
        log(user, Simulation.MKDIR, path);

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

        public String getNextUser(String notThisUser) {
            do {
                int pos = random.nextInt(peergosAndNativeFileSystemPair.size());
                String user = peergosAndNativeFileSystemPair.get(pos).right.user();
                if (user.equals(notThisUser))
                    continue;
                return user;
            } while (true);
        }

        public String getNextUser() {
            return getNextUser(null);
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
                     FileSystems fileSystems,
                     boolean randomizeFriendNetwork) {

        this.fileSystems = fileSystems;
        this.opCount = opCount;
        this.random = random;
        this.getNextSimulation = getNextSimulation;
        this.meanFileLength = meanFileLength;
        this.randomizeFriendNetwork = randomizeFriendNetwork;
        this.index = new FileSystemIndex(random);
    }

    private void readFile(String user, Path path) {
        FileSystem testFileSystem = fileSystems.getTestFileSystem(user);
        FileSystem referenceFileSystem = fileSystems.getReferenceFileSystem(user);
        referenceFileSystem.read(path);
        testFileSystem.read(path);
    }
    private void readSharedFile(String user, Path path) {
        FileSystem testFileSystem = fileSystems.getTestFileSystem(user);
        testFileSystem.read(path);
    }
    private void readSharedDirectory(String user, Path path) {
        FileSystem testFileSystem = fileSystems.getTestFileSystem(user);
        List<Path> paths = testFileSystem.ls(path.getParent());
        if (paths.isEmpty()) {
            testFileSystem.ls(path.getParent());
            throw new IllegalStateException("Unable to read shared directory. user:" + user + " directory:" + path);
        }
    }

    private void log(String user, Simulation simulation, Path path, String... extra) {
        String extraS = Stream.of(extra)
                .collect(Collectors.joining(","));

        String msg = "OP: <" + user + "> " + simulation + " " + path + " " + extraS;
        System.out.println(msg);
    }

    private void write(String user, Path path) {

        byte[] fileContents = getNextFileContents();

        Path dirName = path.getParent();
        String fileName = path.getFileName().toString();
        Map<Path, Set<String>> dirsToFiles = index.getDirToFiles(user);
        Set<String> existingFiles = dirsToFiles.get(dirName);
        existingFiles.add(fileName);
        FileSystem testFileSystem = fileSystems.getTestFileSystem(user);
        FileSystem referenceFileSystem = fileSystems.getReferenceFileSystem(user);

        testFileSystem.write(path, fileContents);
        referenceFileSystem.write(path, fileContents);
    }

    private boolean grantPermission(String granter, String grantee, Path path, FileSystem.Permission permission) {

        FileSystem testFileSystem = fileSystems.getTestFileSystem(granter);
        FileSystem referenceFileSystem = fileSystems.getReferenceFileSystem(granter);

        Set<String> testExistingWriters = new TreeSet<>(testFileSystem.getSharees(path, FileSystem.Permission.WRITE));
        Set<String> refExistingWriters = new TreeSet<>(referenceFileSystem.getSharees(path, FileSystem.Permission.WRITE));
        if (! testExistingWriters.toString().equals(refExistingWriters.toString())) {
            throw new IllegalStateException("WRITE sharing mismatch. test:" + testExistingWriters + " ref:" + refExistingWriters);
        }
        if(testExistingWriters.contains(grantee)) {
            LOG.info("First revoke WRITE permission: user:" + granter + " grantee:" + grantee);
            revokePermission(granter, grantee, path, FileSystem.Permission.WRITE);
        }

        Set<String> testExistingReaders = new TreeSet<>(testFileSystem.getSharees(path, FileSystem.Permission.READ));
        Set<String> refExistingReaders = new TreeSet<>(referenceFileSystem.getSharees(path, FileSystem.Permission.READ));
        if (! testExistingReaders.equals(refExistingReaders)) {
            throw new IllegalStateException("READ sharing mismatch. test:" + testExistingReaders + " ref:" + refExistingReaders);
        }
        if(testExistingReaders.contains(grantee)) {
            LOG.info("First revoke READ permission: user:" + granter + " grantee:" + grantee);
            revokePermission(granter, grantee, path, FileSystem.Permission.READ);
        }

        testFileSystem.grant(path, grantee, permission);
        referenceFileSystem.grant(path, grantee, permission);

        return true;
    }

    private boolean revokePermission(String revoker, String revokee, Path path, FileSystem.Permission permission) {
        FileSystem testFileSystem = fileSystems.getTestFileSystem(revoker);
        FileSystem referenceFileSystem = fileSystems.getReferenceFileSystem(revoker);

        testFileSystem.revoke(path, revokee, permission);
        referenceFileSystem.revoke(path, revokee, permission);

        return true;
    }

    private void init() {
        List<String> users = this.fileSystems.getUsers();
        Collections.sort(users);

        for (String leftUser : users) {
            index.addUser(leftUser);
            index.getDirToFiles(leftUser).put(PathUtil.get("/" + leftUser), new HashSet<>());
            // seed the file-system
            run(Simulation.MKDIR, leftUser);
            run(Simulation.WRITE_OWN_FILE, leftUser);

            for (String rightUser : users) {
                if (leftUser.compareTo(rightUser) >= 0)
                    continue;

                if (! randomizeFriendNetwork) {
                    fileSystems.getTestFileSystem(leftUser).follow(fileSystems.getTestFileSystem(rightUser), true);
                    fileSystems.getReferenceFileSystem(leftUser).follow(fileSystems.getReferenceFileSystem(rightUser), true);
                }
                else {
                    // If this seems overly-complicated see https://github.com/Peergos/Peergos/issues/638
                    boolean leftFollowRight = random.nextBoolean();
                    boolean rightFollowLeft= random.nextBoolean();
                    if (leftFollowRight && rightFollowLeft) {
                        fileSystems.getTestFileSystem(leftUser).follow(fileSystems.getTestFileSystem(rightUser), true);
                        fileSystems.getReferenceFileSystem(leftUser).follow(fileSystems.getReferenceFileSystem(rightUser), true);
                    }
                    else if (leftFollowRight) {
                        fileSystems.getTestFileSystem(leftUser).follow(fileSystems.getTestFileSystem(rightUser), false);
                        fileSystems.getReferenceFileSystem(leftUser).follow(fileSystems.getReferenceFileSystem(rightUser), false);
                    }
                    else if (rightFollowLeft) {
                        fileSystems.getTestFileSystem(rightUser).follow(fileSystems.getTestFileSystem(leftUser), false);
                        fileSystems.getReferenceFileSystem(rightUser).follow(fileSystems.getReferenceFileSystem(leftUser), false);
                    }
                }
            }

        }
    }

    private void run(Simulation simulation) {
        String nextUser = fileSystems.getNextUser();
        run(simulation, nextUser);
    }

    private void run(final Simulation simulation, final String user) {
        Supplier<String> otherUser = () -> fileSystems.getNextUser(user);
        Supplier<Path> randomFilePathForUser = () -> index.getRandomExistingFile(user);
        Supplier<Path> randomFolderPathForUser = () -> index.getRandomExistingDirectory(user, true);
        BiFunction<String, String, Optional<Path>> randomSharedPath = (owner, sharee) -> {
            try {
                Path path = fileSystems.getReferenceFileSystem(owner).getRandomSharedPath(random, simulation.permission(), sharee);
                return Optional.of(path);
            } catch (IllegalStateException ile) {
                //Nothing  shared yet
                return Optional.empty();
            }
        };
        TriFunction<String, String, Boolean, Optional<Path>> randomSharedPathWithRetries = (owner, sharee, isDirectory) -> {
            int keepTrying = 0;
            while (keepTrying < 3) {
                Optional<Path> path = randomSharedPath.apply(owner, sharee);
                if (path.isPresent()) {
                    Stat stat = fileSystems.getReferenceFileSystem(owner).stat(path.get());
                    if (isDirectory && stat.fileProperties().isDirectory) {
                        return path;
                    } else if (! isDirectory && ! stat.fileProperties().isDirectory) {
                        return path;
                    }
                }
                keepTrying++;
            }
            return Optional.empty();
        };
        BiFunction<String, String, Optional<Path>> randomSharedDirectoryPath = (owner, sharee) ->
             randomSharedPathWithRetries.apply(owner, sharee, true);

        BiFunction<String, String, Optional<Path>> randomSharedFilePath = (owner, sharee) ->
             randomSharedPathWithRetries.apply(owner, sharee, false);


        switch (simulation) {
            case READ_OWN_FILE:
                Path readPath = randomFilePathForUser.get();
                log(user, simulation, readPath);
                readFile(user, readPath);
                break;
            case READ_SHARED_FILE:
                Optional<Path> sharedOpt = randomSharedFilePath.apply(otherUser.get(), user);
                if (! sharedOpt.isPresent())
                    return;
                Path sharedPathToRead = sharedOpt.get();
                log(user, Simulation.READ_SHARED_FILE, sharedPathToRead);
                readSharedFile(user, sharedPathToRead);
                break;
            case READ_SHARED_DIRECTORY:
                Optional<Path> sharedDirOpt = randomSharedDirectoryPath.apply(otherUser.get(), user);
                if (! sharedDirOpt.isPresent())
                    return;
                Path sharedDirPathToRead = sharedDirOpt.get();
                log(user, Simulation.READ_SHARED_DIRECTORY, sharedDirPathToRead);
                readSharedDirectory(user, sharedDirPathToRead);
                break;
            case WRITE_OWN_FILE:
                Path path = getAvailableFilePath(user);
                log(user, Simulation.WRITE_OWN_FILE, path);
                write(user, path);
                break;
            case WRITE_SHARED_FILE:
                Optional<Path> sharedOpt2 = randomSharedFilePath.apply(user, otherUser.get());
                if (! sharedOpt2.isPresent())
                    return;
                Path  sharedPathToWrite = sharedOpt2.get();
                log(user, Simulation.WRITE_SHARED_FILE, sharedPathToWrite);
                write(user, sharedPathToWrite);
                break;
            case WRITE_SHARED_DIRECTORY:
                Optional<Path> sharedOpt3 = randomSharedDirectoryPath.apply(user, otherUser.get());
                if (! sharedOpt3.isPresent())
                    return;
                Path  sharedDirectoryPathToWrite = sharedOpt3.get();
                log(user, Simulation.WRITE_SHARED_DIRECTORY, sharedDirectoryPathToWrite);
                mkdir(user, sharedDirectoryPathToWrite.resolve(getNextName()));
                break;
            case MKDIR:
                mkdir(user);
                break;
            case RM:
                rm(user);
                break;
            case RMDIR:
                rmdir(user);
                //ensure not shared

                break;
            case GRANT_READ_FILE:
            case GRANT_WRITE_FILE:
                Path grantFilePath = randomFilePathForUser.get();
                String fileGrantee = otherUser.get();
                log(user, simulation, grantFilePath, "with grantee "+ fileGrantee);
                grantPermission(user, fileGrantee, grantFilePath, simulation.permission());
                break;
            case GRANT_READ_DIR:
            case GRANT_WRITE_DIR:
                Path grantDirPath = randomFolderPathForUser.get();
                String dirGrantee = otherUser.get();
                log(user, simulation, grantDirPath, "with grantee "+ dirGrantee);
                grantPermission(user, dirGrantee, grantDirPath, simulation.permission());
                break;
            case REVOKE_READ:
            case REVOKE_WRITE:
                String revokee = otherUser.get();
                Optional<Path> revokeOpt = randomSharedPath.apply(user, revokee);
                if (! revokeOpt.isPresent())
                    return;
                Path revokePath = revokeOpt.get();
                log(user, simulation, revokePath);
                revokePermission(user, revokee, revokePath, simulation.permission());
                break;
            default:
                throw new IllegalStateException("Unexpected simulation " + simulation);
        }
        simulationRecord.add(simulation);
    }

    public void run(boolean verifyAll) {
        LOG.info("Running file-system IO-simulation");

        init();

        for (int iOp = 2; iOp < opCount; iOp++) {
            Simulation simulation = getNextSimulation.get();
            System.out.println("iOp=" + iOp + " " + simulation);
            run(simulation);

            if (verifyAll) {
                boolean isVerified = verify();
                if (!isVerified) {
                    isVerified = verify();
                    throw new Error("FAILED VERIFICATION!");
                }
            }
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
        Stat stat = fileSystems.getReferenceFileSystem(user).stat(path);
        boolean isDir = stat.fileProperties().isDirectory;

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
                if (isDir) {
                    switch (permission) {
                        case READ:
                            try {
                                //can read?
                                fs.ls(path);
                            } catch (Exception ex) {
                                LOG.log(Level.SEVERE, "User " + sharee + " could not read shared-path " + path + "!", ex);
                                isVerified = false;
                            }
                            break;
                        case WRITE:
                            try {
                                //can read?
                                fs.ls(path);
                            } catch (Exception ex) {
                                LOG.log(Level.SEVERE, "User " + sharee + " could not read a writable shared-path " + path + "!", ex);
                                isVerified = false;
                            }
                            if (isVerified) {
                                try {
                                    mkdir(user, path.resolve(getNextName()));
                                } catch (Exception ex) {
                                    LOG.log(Level.SEVERE, "User " + sharee + " could not write  shared-path " + path + "!", ex);
                                    isVerified = false;
                                }
                            }
                            break;
                        default:
                            throw new IllegalStateException();
                    }
                } else {
                    switch (permission) {
                        case READ:
                            try {
                                //can read?
                                read = fs.read(path);
                            } catch (Exception ex) {
                                LOG.log(Level.SEVERE, "User " + sharee + " could not read shared-path " + path + "!", ex);
                                isVerified = false;
                            }
                            break;
                        case WRITE:
                            try {
                                //can read?
                                read = fs.read(path);
                            } catch (Exception ex) {
                                LOG.log(Level.SEVERE, "User " + sharee + " could not read shared-path " + path + "!", ex);
                                isVerified = false;
                            }
                            if (isVerified) {
                                try {
                                    // can overwrite?
                                    fs.modify(path, read);
                                } catch (Exception ex) {
                                    LOG.log(Level.SEVERE, "User " + sharee + " could not write  shared-path " + path + "!", ex);
                                    isVerified = false;
                                }
                            }
                            break;
                        default:
                            throw new IllegalStateException();
                    }
                }
                if (! isVerified) {
                    break;
                }
            }


        }
        return isVerified;
    }

    private boolean verifyContents(String user, Path path) {
        FileSystem peergosFileSystem = fileSystems.getTestFileSystem(user);
        FileSystem nativeFileSystem = fileSystems.getReferenceFileSystem(user);
        Stat stat = fileSystems.getReferenceFileSystem(user).stat(path);
        boolean isDir = stat.fileProperties().isDirectory;
        try {
            if (isDir) {
                Set<Path> refDirectoryListing = new TreeSet<>(nativeFileSystem.ls(path, true));
                Set<Path> testDirectoryListing = new TreeSet<>(peergosFileSystem.ls(path, false));
                if (!refDirectoryListing.toString().equalsIgnoreCase(testDirectoryListing.toString())) {
                    LOG.info("Path " + path + " has different directory contents between the file-systems");
                    return false;
                }
            } else {
                byte[] testData = peergosFileSystem.read(path);
                byte[] refData = nativeFileSystem.read(path);
                if (!Arrays.equals(testData, refData)) {
                    LOG.info("Path " + path + " has different contents between the file-systems");
                    return false;
                }
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

            Map<Path, Set<String>> dirToFiles = index.getDirToFiles(user);
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
            Set<Path> allFilesAndFolders = new HashSet<>(expectedFilesForUser);
            allFilesAndFolders.addAll(dirToFiles.keySet());
            for (Path path : allFilesAndFolders) {
                boolean verifyContents = verifyContents(user, path);
                if (! verifyContents)
                    verifyContents(user, path);
                isUserVerified &= verifyContents;
                boolean sharingPermissionsAreVerified = verifySharingPermissions(user, path);
                isUserVerified &= sharingPermissionsAreVerified;
                if (! sharingPermissionsAreVerified)
                    verifySharingPermissions(user, path);
            }
            if (! isUserVerified) {
                LOG.info("User " + user + " is not verified!");
                isGlobalVerified = false;
                break;
            }


        }
        return isGlobalVerified;
    }

    private static String usernameToPassword(String username) {
        return username + "_password";
    }

    public static void main(String[] a) throws Exception {
        Crypto crypto = Main.initCrypto();
        Args args = buildArgs()
//                .with("useIPFS", "true")
                .with("useIPFS", "false")
                .with("peergos.password", "testpassword")
                .with("pki.keygen.password", "testpkipassword")
                .with("pki.keyfile.password", "testpassword")
                .with(IpfsWrapper.IPFS_BOOTSTRAP_NODES, ""); // no bootstrapping
        UserService service = Main.PKI_INIT.main(args).localApi;
        LOG.info("***NETWORK READY***");

        Function<String, Pair<FileSystem, FileSystem>> fsPairBuilder = username -> {
            try {
                WriteSynchronizer synchronizer = new WriteSynchronizer(service.mutable, service.storage, crypto.hasher);
                MutableTree mutableTree = new MutableTreeImpl(service.mutable, service.storage, crypto.hasher, synchronizer);
                NetworkAccess networkAccess = new NetworkAccess(service.coreNode, service.account, service.social, service.storage,
                        service.bats, Optional.empty(), service.mutable, mutableTree, synchronizer, service.controller, service.usage,
                        service.serverMessages, service.crypto.hasher, Arrays.asList("peergos"), false);
                UserContext userContext = PeergosNetworkUtils.ensureSignedUp(username, usernameToPassword(username), networkAccess, crypto);
                PeergosFileSystemImpl peergosFileSystem = new PeergosFileSystemImpl(userContext);
                Path root = Files.createTempDirectory("test_filesystem-" + username);
                AccessControl accessControl = new AccessControl.MemoryImpl();
                NativeFileSystemImpl nativeFileSystem = new NativeFileSystemImpl(root, username, accessControl);
                return new Pair<>(peergosFileSystem, nativeFileSystem);
            } catch (Exception ioe) {
                throw new IllegalStateException(ioe);
            }
        };

        Map<Simulation, Double> probabilities = Stream.of(
                new Pair<>(Simulation.READ_OWN_FILE, 0.0),
                new Pair<>(Simulation.READ_SHARED_FILE, 0.1),
                new Pair<>(Simulation.READ_SHARED_DIRECTORY, 0.1),
                new Pair<>(Simulation.WRITE_OWN_FILE, 0.4),
                new Pair<>(Simulation.WRITE_SHARED_FILE, 0.1),
                new Pair<>(Simulation.WRITE_SHARED_DIRECTORY, 0.1),
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

        class SimulationSupplier implements Supplier<Simulation> {
            private final Simulation[] simulations;
            private final double[] cumulativeProbabilities;
            private final Random random;
            private final double probabililtyNorm;

            @Override
            public Simulation get() {
                double v = random.nextDouble() * probabililtyNorm;
                int pos = Arrays.binarySearch(cumulativeProbabilities, v);
                if (pos < 0)
                    pos = Math.max(0, -pos -1-1);
                return simulations[pos];
            }

            public SimulationSupplier(Map<Simulation, Double> probabilities, Random random) {
                // remove simulations with empty probabilities
                probabilities = probabilities.entrySet()
                        .stream()
                        .filter(e -> e.getValue()  > 0)
                        .collect(Collectors.toMap(e -> e.getKey(), e ->  e.getValue()));

                this.simulations = new Simulation[probabilities.size()];
                this.cumulativeProbabilities = new double[probabilities.size()];
                this.random  = random;
                int pos = 0;
                double acc = 0;

                List<Simulation> sortedSims = probabilities.keySet()
                        .stream()
                        .sorted()
                        .collect(Collectors.toList());

                for (Simulation sim : sortedSims) {
                    Double prob = probabilities.get(sim);
                    acc += prob;
                    simulations[pos] = sim;
                    cumulativeProbabilities[pos] = acc;
                    pos++;
                }
                this.probabililtyNorm = acc;


            }
        }
        Args simulatorArgs = Args.parse(a);
        int opCount = 2000;
        boolean verifyAll = true;
        int seed = simulatorArgs.getInt("random-seed", 1);
        int nUsers = simulatorArgs.getInt("n-users", 3);
        int meanFileLength  = simulatorArgs.getInt("mean-file-length", 256);
        boolean randomizeFriendNetwork = simulatorArgs.getBoolean("randomize-friend-network", false);
        final Random random = new Random(seed);

        Supplier<Simulation> getNextSimulation = new SimulationSupplier(probabilities, random);

        //hard-mode
        CryptreeNode.setMaxChildLinkPerBlob(10);

        List<Pair<FileSystem, FileSystem>> fs = IntStream.range(0, nUsers)
                .mapToObj(i -> String.format("user_%d", i))
                .map(fsPairBuilder).collect(Collectors.toList());;

        FileSystems fileSystems = new FileSystems(fs, random);
        Simulator simulator = new Simulator(opCount, random, meanFileLength, getNextSimulation, fileSystems, randomizeFriendNetwork);

        try {
            simulator.run(verifyAll);
        } catch (Throwable t) {
            t.printStackTrace();
            LOG.log(Level.SEVERE, t, () -> "So long");
        } finally {
            System.exit(0);
        }

    }
}
