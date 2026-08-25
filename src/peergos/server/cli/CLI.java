package peergos.server.cli;

import org.jline.builtins.*;
import org.jline.reader.*;
import org.jline.reader.impl.*;
import org.jline.reader.impl.completer.AggregateCompleter;
import org.jline.reader.impl.completer.ArgumentCompleter;
import org.jline.reader.impl.completer.NullCompleter;
import org.jline.reader.impl.completer.StringsCompleter;
import org.jline.terminal.*;
import org.jline.utils.*;

import peergos.server.*;
import peergos.server.crypto.hash.ScryptJava;
import peergos.server.net.ProxyChooser;
import peergos.server.simulation.*;
import peergos.server.simulation.FileSystem;
import peergos.server.user.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.corenode.HTTPCoreNode;
import peergos.shared.crypto.asymmetric.PublicSigningKey;
import peergos.shared.crypto.hash.Hasher;
import peergos.shared.login.mfa.*;
import peergos.shared.mutable.HttpMutablePointers;
import peergos.shared.social.FollowRequestWithCipherText;
import peergos.shared.social.HttpSocialNetwork;
import peergos.shared.storage.HttpSpaceUsage;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.user.fs.archive.*;
import peergos.shared.util.*;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.util.logging.Level;
import java.util.stream.*;

import static org.jline.builtins.Completers.TreeCompleter.node;

public class CLI implements Runnable {

    private static void disableLogSpam() {
        // disable log spam
        TrieNodeImpl.disableLog();
        HttpMutablePointers.disableLog();
        NetworkAccess.disableLog();
        HTTPCoreNode.disableLog();
        HttpSocialNetwork.disableLog();
        HttpSpaceUsage.disableLog();
        FileUploader.disableLog();
        LazyInputStreamCombiner.disableLog();
    }

    private final CLIContext cliContext;
    private final FileSystem peergosFileSystem;
    private final ArchiveNavigator archives;
    private final ListFilesCompleter remoteFilesCompleter, localFilesCompleter, remoteDirsCompleter, localDirsCompleter;
    private final Completer allUsernamesCompleter, followersCompleter, pendingFollowersCompleter, processFollowRequestCompleter;
    private volatile boolean isFinished;

    public CLI(CLIContext cliContext) {
        this.cliContext = cliContext;
        this.peergosFileSystem = new PeergosFileSystemImpl(cliContext.userContext);
        this.archives = new ArchiveNavigator(peergosFileSystem);
        this.remoteFilesCompleter = new ListFilesCompleter(path -> this.remoteFilesLsFiles(path, false));
        this.remoteDirsCompleter = new ListFilesCompleter(path -> this.remoteFilesLsFiles(path, true));
        this.localFilesCompleter = new ListFilesCompleter(path -> this.localFilesLsFiles(path, false));
        this.localDirsCompleter = new ListFilesCompleter(path -> this.localFilesLsFiles(path, true));
        this.allUsernamesCompleter = new SupplierCompleter(this::listAllUsernames);
        this.followersCompleter = new SupplierCompleter(this::listFollowers);
        this.pendingFollowersCompleter = new SupplierCompleter(this::listPendingFollowers);
        this.processFollowRequestCompleter = new StringsCompleter(
                Stream.of(Command.ProcessFollowRequestAction.values())
                        .map(Command.ProcessFollowRequestAction::altOrName)
                        .collect(Collectors.toList()));
    }

    /**
     * resolve against remote pwd if path is relative
     *
     * @param path
     * @return
     */
    public Path resolvedRemotePath(String path) {
        return resolveToPath(path, cliContext.pwd);
    }

    public Path resolveToPath(String arg, Path pathToResolveTo) {
        Path p = Paths.get(arg);
        if (p.isAbsolute())
            return p;
        return pathToResolveTo.resolve(p).normalize();
    }

    public Path resolveToPath(String arg) {
        return resolveToPath(arg, cliContext.lpwd);
    }

    public static ParsedCommand fromLine(String line) {
        String[] split = line.trim().split("\\s+");
        if (split == null || split.length == 0)
            throw new IllegalStateException();
        ArrayList<String> tokens = new ArrayList<>(Arrays.asList(split));

        Command cmd = Command.parse(tokens.remove(0));

        return new ParsedCommand(cmd, line, tokens);
    }

    private static final char PASSWORD_MASK = '*';
    private static final String PROMPT = " > ";
    private static final int CAT_BUFFER_SIZE = 32 * 1024;
    private static final DateTimeFormatter MODIFIED_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    static String formatHelp() {
        StringBuilder sb = new StringBuilder();
        sb.append("Available commands:");
        int maxLength = Command.maxLength();

        for (Command cmd : Arrays.asList(Command.values())) {
            sb.append("\n").append(cmd.example());
            for (int i = 0; i < maxLength - cmd.example().length(); i++) {
                sb.append(" ");
            }
            sb.append("\t").append(cmd.description);
        }
        sb.append("\n\nNote: <TAB> based autocomplete is available on most commands.");
        return sb.toString();
    }

    private String handle(ParsedCommand parsedCommand, Terminal terminal, LineReader reader) {


        try {
            switch (parsedCommand.cmd) {
                case ls:
                    return ls(parsedCommand);
                case lls:
                    return lls(parsedCommand);
                case get:  // download
                    return get(parsedCommand, terminal.writer());
                case cat:
                    return cat(parsedCommand, terminal.writer());
                case put:  //upload
                    return put(parsedCommand, terminal.writer());
                case mkdir:
                    return mkdir(parsedCommand);
                case rm:
                    return rm(parsedCommand);
                case mv:
                    return mv(parsedCommand);
                case exit:
                case quit:
                case bye:
                    return exit(parsedCommand);
                case help:
                    return help(parsedCommand);
                case space:
                    return space(parsedCommand);
                case get_follow_requests:
                    return getFollowRequests(parsedCommand);
                case process_follow_request:
                    return processFollowRequest(parsedCommand);
                case follow:
                    return follow(parsedCommand);
                case passwd:
                    return passwd(parsedCommand, terminal, reader);
//                case share:
                case share_read:
                    return shareReadAccess(parsedCommand);
                case share_write:
                    return shareWriteAccess(parsedCommand);
                case make_public:
                    return makePublic(parsedCommand);
                case cd:
                    return cd(parsedCommand);
                case lcd:
                    return lcd(parsedCommand);
                case pwd:
                    return pwd(parsedCommand);
                case lpwd:
                    return lpwd(parsedCommand);
                default:
                    return "Unexpected cmd '" + parsedCommand.cmd + "'";
            }
        } catch (Exception ex) {
            ex.printStackTrace();
            return "Failed to execute " + parsedCommand;

        }

    }

    public String ls(ParsedCommand cmd) {

        String pathArg = cmd.hasArguments() ? cmd.firstArgument() : "";
        Path path = resolvedRemotePath(pathArg);
        boolean longFormat = cmd.hasFlag(Command.Flag.LONG);

        ArchiveNavigator.Target target = resolve(path);
        if (target.isArchive())
            return lsArchive(target, longFormat);

        Stat stat = target.stat;
        if (! stat.fileProperties().isDirectory)
            return longFormat ? formatLong(stat) : path.toString();

        if (longFormat)
            return peergosFileSystem.lsStats(path, false).stream()
                    .sorted(Comparator.comparing(s -> s.fileProperties().name))
                    .map(CLI::formatLong)
                    .collect(Collectors.joining("\n"));

        return peergosFileSystem.ls(path, false).stream()
                .map(p -> p.getFileName().toString())
                .sorted()
                .collect(Collectors.joining("\n"));
    }

    /** The archive's file, fetched afresh: every write to an archive replaces it.
     */
    private FileWrapper archiveFile(ArchiveNavigator.Target target) {
        return cliContext.userContext.getByPath(target.path.toString()).join()
                .orElseThrow(() -> new IllegalStateException("Could not find " + target.path));
    }

    private void putInArchive(ArchiveNavigator.Target target, Path localPath, long size, PrintWriter writerForProgress) {
        ProgressBar pb = new ProgressBar(new AtomicLong(0), new AtomicLong(1), target.path, localPath.getFileName().toString());
        // the file is read twice, once to compress it and once to write it
        ZipWriter.NewEntry entry = new ZipWriter.NewEntry(target.entry, size,
                LocalDateTime.ofEpochSecond(localPath.toFile().lastModified() / 1000, 0, ZoneOffset.UTC),
                () -> Futures.of(new FileAsyncReader(localPath.toFile())));
        ZipWriter.append(archiveFile(target), Collections.singletonList(entry),
                cliContext.userContext.network, cliContext.userContext.crypto,
                bytes -> pb.update(writerForProgress, bytes, 2 * size)).join();
        archives.forget();
        writerForProgress.println();
        writerForProgress.flush();
    }

    private ArchiveNavigator.Target resolve(Path remotePath) {
        return archives.resolve(remotePath)
                .orElseThrow(() -> new IllegalStateException("Could not find remote specified remote path '" + remotePath + "'"));
    }

    private String lsArchive(ArchiveNavigator.Target target, boolean longFormat) {
        ZipReader zip = archives.open(target);
        if (! target.entry.isEmpty()) {
            ZipEntry entry = archives.entry(target);
            if (! entry.isDirectory)
                return longFormat ? formatLong(entry) : target.fullPath().toString();
        }
        return zip.listDirectory(target.entry).stream()
                .sorted(Comparator.comparing(ZipEntry::getName))
                .map(e -> longFormat ? formatLong(e) : e.getName())
                .collect(Collectors.joining("\n"));
    }

    /** Entries in an archive are readable but, until archives can be edited, never writable.
     */
    public static String formatLong(ZipEntry entry) {
        return String.format("%s%s-  %9s  %s  %s",
                entry.isDirectory ? "d" : "-",
                entry.isSupported() ? "r" : "-",
                entry.isDirectory ? "-" : formatSize(entry.size),
                entry.modified.format(MODIFIED_FORMAT),
                entry.getName() + (entry.isDirectory ? "/" : ""));
    }

    public static String formatLong(Stat stat) {
        FileProperties props = stat.fileProperties();
        return String.format("%s%s%s  %9s  %s  %s",
                props.isDirectory ? "d" : "-",
                stat.isReadable() ? "r" : "-",
                stat.isWritable() ? "w" : "-",
                props.isDirectory ? "-" : formatSize(props.size),
                props.modified.format(MODIFIED_FORMAT),
                props.name + (props.isDirectory ? "/" : ""));
    }

    public static String formatSize(long bytes) {
        if (bytes < 1024)
            return bytes + " B";
        String[] units = {"KiB", "MiB", "GiB", "TiB"};
        double size = bytes;
        int unit = -1;
        while (size >= 1024 && unit < units.length - 1) {
            size /= 1024;
            unit++;
        }
        return String.format(size < 10 ? "%.1f %s" : "%.0f %s", size, units[unit]);
    }

    public String lls(ParsedCommand cmd) {

        String localPathArg = cmd.hasArguments() ? cmd.firstArgument() : "";
        Path path = resolveToPath(localPathArg).toAbsolutePath().normalize();

        try {
            if (path.toFile().isDirectory())
                return Files.list(path)
                        .map(Path::toString)
                        .sorted()
                        .collect(Collectors.joining("\n"));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        return path.toString();
    }

    private Stat checkPath(Path remotePath) {
        Stat stat = null;
        try {
            return peergosFileSystem.stat(remotePath);
        } catch (Exception ex) {
            throw new IllegalStateException("Could not find remote specified remote path '" + remotePath + "'", ex);
        }

    }

    public String get(ParsedCommand cmd, PrintWriter writerForProgress) throws IOException {
        if (!cmd.hasArguments())
            throw new IllegalStateException();

        Path remotePath = resolvedRemotePath(cmd.firstArgument()).toAbsolutePath().normalize();

        ArchiveNavigator.Target target = resolve(remotePath);
        if (target.isInArchive())
            return getFromArchive(target, cmd, writerForProgress);

        Stat stat = target.stat;

        String localPathArg = cmd.hasSecondArgument() ? cmd.secondArgument() : "";
        Path localPath = resolveToPath(localPathArg).toAbsolutePath();

        if (localPath.toFile().isDirectory())
            localPath = localPath.resolve(stat.fileProperties().name);
        else if (!localPath.toFile().getParentFile().isDirectory())
            throw new IllegalStateException("Specified local path '" + localPath.getParent() + "' is not a directory or does not exist.");

        if (stat.fileProperties().isDirectory) {
            boolean skipExisting = cmd.flags.contains(Command.Flag.SKIP_EXISTING.flag);
            copyDir(remotePath, localPath.getParent(), skipExisting, writerForProgress);
            return "Downloaded " + remotePath + " to " + localPath;
        } else {
            download(peergosFileSystem.reader(remotePath), stat.fileProperties().size, localPath,
                    remotePath.getParent(), remotePath.getFileName().toString(), writerForProgress);
            return "Downloaded " + remotePath + " to " + localPath;
        }
    }

    /** Stream size bytes from reader into a local file, showing progress.
     */
    private void download(AsyncReader reader,
                          long size,
                          Path localFile,
                          Path remoteParent,
                          String name,
                          PrintWriter writerForProgress) throws IOException {
        ProgressBar pb = new ProgressBar(new AtomicLong(0), new AtomicLong(1), remoteParent, name);
        byte[] buf = new byte[Chunk.MAX_SIZE];
        try (FileOutputStream fout = new FileOutputStream(localFile.toFile())) {
            for (long offset = 0; offset < size;) {
                int read = reader.readIntoArray(buf, 0, (int) Math.min(buf.length, size - offset)).join();
                fout.write(buf, 0, read);
                offset += read;
                pb.update(writerForProgress, offset, size);
            }
        }
        writerForProgress.println();
        writerForProgress.flush();
    }

    private String getFromArchive(ArchiveNavigator.Target target, ParsedCommand cmd, PrintWriter writerForProgress) throws IOException {
        ZipReader zip = archives.open(target);
        ZipEntry entry = archives.entry(target);

        String localPathArg = cmd.hasSecondArgument() ? cmd.secondArgument() : "";
        Path localPath = resolveToPath(localPathArg).toAbsolutePath();
        if (localPath.toFile().isDirectory())
            localPath = localPath.resolve(entry.getName());
        else if (! localPath.toFile().getParentFile().isDirectory())
            throw new IllegalStateException("Specified local path '" + localPath.getParent() + "' is not a directory or does not exist.");

        if (entry.isDirectory)
            copyArchiveDir(zip, entry, localPath, target.path, cmd.flags.contains(Command.Flag.SKIP_EXISTING.flag), writerForProgress);
        else
            download(zip.read(entry).join(), entry.size, localPath, target.path, entry.getName(), writerForProgress);
        return "Downloaded " + target.fullPath() + " to " + localPath;
    }

    private void copyArchiveDir(ZipReader zip,
                                ZipEntry dir,
                                Path localDir,
                                Path archive,
                                boolean skipExisting,
                                PrintWriter writerForProgress) throws IOException {
        if (! localDir.toFile().exists())
            localDir.toFile().mkdirs();
        if (! localDir.toFile().isDirectory())
            throw new IllegalStateException(localDir + " already exists and is a file not a directory!");
        for (ZipEntry child : zip.listDirectory(dir.path)) {
            Path localChild = localDir.resolve(child.getName());
            if (child.isDirectory)
                copyArchiveDir(zip, child, localChild, archive, skipExisting, writerForProgress);
            else if (localChild.toFile().exists() && skipExisting)
                writerForProgress.println("Skipping " + localChild);
            else
                download(zip.read(child).join(), child.size, localChild,
                        archive.resolve(child.getParentPath()), child.getName(), writerForProgress);
        }
    }

    private void copyDir(Path remote, Path local, boolean skipExisting, PrintWriter writerForProgress) throws IOException {
        String dirName = remote.getFileName().toString();
        Path localDir = local.resolve(dirName);
        if (! localDir.toFile().exists())
            localDir.toFile().mkdirs();
        if (! localDir.toFile().isDirectory())
            throw new IllegalStateException(localDir + " already exists and is a file not a directory!");
        List<Path> remoteChildren = peergosFileSystem.ls(remote);
        for (Path remoteChild : remoteChildren) {
            Stat stat = peergosFileSystem.stat(remoteChild);
            if (stat.fileProperties().isDirectory) {
                copyDir(remoteChild, localDir, skipExisting, writerForProgress);
            } else {
                File localFile = localDir.resolve(remoteChild.getFileName()).toFile();
                if (localFile.exists() && skipExisting) {
                    writerForProgress.println("Skipping " + localFile);
                    continue;
                }
                download(peergosFileSystem.reader(remoteChild), stat.fileProperties().size, localFile.toPath(),
                        remoteChild.getParent(), remoteChild.getFileName().toString(), writerForProgress);
            }
        }
    }

    public String cat(ParsedCommand cmd, PrintWriter writer) throws IOException {
        if (! cmd.hasArguments())
            throw new IllegalStateException("Usage: " + Command.cat.example());

        Path remotePath = resolvedRemotePath(cmd.firstArgument()).toAbsolutePath().normalize();
        ArchiveNavigator.Target target = resolve(remotePath);
        if (target.isInArchive()) {
            ZipEntry entry = archives.entry(target);
            if (entry.isDirectory)
                throw new IllegalStateException("'" + remotePath + "' is a directory.");
            writeTextTo(archives.open(target).read(entry).join(), entry.size, writer, CAT_BUFFER_SIZE);
            return "";
        }
        Stat stat = target.stat;
        if (stat.fileProperties().isDirectory)
            throw new IllegalStateException("'" + remotePath + "' is a directory.");

        writeTextTo(peergosFileSystem.reader(remotePath), stat.fileProperties().size, writer, CAT_BUFFER_SIZE);
        return "";
    }

    /** Stream the first fileSize bytes of reader to writer, decoded as UTF-8.
     */
    public static void writeTextTo(AsyncReader reader, long fileSize, PrintWriter writer, int bufferSize) {
        // decode incrementally so a multi-byte character spanning two reads survives
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        byte[] buf = new byte[bufferSize];
        ByteBuffer bytes = ByteBuffer.allocate(buf.length + 8);
        CharBuffer chars = CharBuffer.allocate(buf.length + 8);
        for (long offset = 0; offset < fileSize;) {
            int read = reader.readIntoArray(buf, 0, Math.min(buf.length, (int) (fileSize - offset))).join();
            offset += read;
            bytes.put(buf, 0, read);
            bytes.flip();
            decoder.decode(bytes, chars, offset == fileSize);
            bytes.compact();
            chars.flip();
            writer.write(chars.toString());
            chars.clear();
        }
        if (fileSize > 0) { // a decoder that has never decoded anything cannot be flushed
            decoder.flush(chars);
            chars.flip();
            writer.write(chars.toString());
        }
        writer.flush();
    }

    private static List<String> convert(Path p) {
        List<String> res = new ArrayList<>();
        for (int i=0; i < p.getNameCount(); i++)
            res.add(p.getName(i).toString());
        return res;
    }

    private static AsyncReader reader(File f) {
        return new FileAsyncReader(f);
    }

    public interface ProgressCreator {
        ProgressConsumer<Long> create(Path remoteRelativeDir, String filename, Long size);
    }

    public static Stream<FileWrapper.FolderUploadProperties> parseLocalFolder(Path remoteRelativeDir,
                                                                        Path localDir,
                                                                        boolean skipExisting,
                                                                        AtomicLong fileCount,
                                                                        Hasher hasher,
                                                                        ProgressCreator progressCreator) {
        try {
            List<FileWrapper.FileUploadProperties> files = Files.list(localDir)
                    .filter(p -> p.toFile().isFile())
                    .map(p -> {
                        long fileSize = p.toFile().length();
                        LocalDateTime modified = LocalDateTime.ofInstant(Instant.ofEpochSecond(p.toFile().lastModified() / 1000, 0), ZoneOffset.UTC);
                        return new FileWrapper.FileUploadProperties(p.getFileName().toString(), () -> reader(p.toFile()),
                                (int) (fileSize >> 32), (int) fileSize, Optional.of(modified), Optional.of(ScryptJava.hashFile(p, hasher)), skipExisting, true,
                                progressCreator.create(remoteRelativeDir, p.getFileName().toString(), Math.max(4096, fileSize)));
                    })
                    .collect(Collectors.toList());
            fileCount.addAndGet(files.size());
            FileWrapper.FolderUploadProperties dir = new FileWrapper.FolderUploadProperties(convert(remoteRelativeDir), files);
            return Stream.concat(Stream.of(dir),
                    Files.list(localDir)
                            .filter(p -> p.toFile().isDirectory())
                            .flatMap(p -> parseLocalFolder(remoteRelativeDir.resolve(p.getFileName()), localDir.resolve(p.getFileName()), skipExisting, fileCount, hasher, progressCreator)));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public String put(ParsedCommand cmd, PrintWriter writerForProgress) throws IOException {
        String localPathArg = cmd.firstArgument();
        Path localPath = resolveToPath(localPathArg).toAbsolutePath().normalize();

        if (localPath.toFile().isDirectory()) {
            Path remotePath = cmd.hasSecondArgument() ? cliContext.pwd.resolve(Paths.get(cmd.secondArgument())) : cliContext.pwd;
            boolean skipExisting = cmd.flags.contains(Command.Flag.SKIP_EXISTING.flag);
            AtomicLong fileCount = new AtomicLong(0);
            AtomicLong doneFiles = new AtomicLong(0);
            peergosFileSystem.writeSubtree(remotePath, parseLocalFolder(localPath.getFileName(), localPath, skipExisting, fileCount,
                    cliContext.userContext.crypto.hasher, (path, name, size) -> {
                        ProgressBar pb = new ProgressBar(doneFiles, fileCount, path, name);
                        return bytesWritten -> pb.update(writerForProgress, bytesWritten, size);
                    }), f -> Futures.of(true));
            return "\nSuccessfully uploaded " + localPath + " to remote " + remotePath;
        } else {
            String remotePathS = cmd.hasSecondArgument() ? cmd.secondArgument() : cliContext.pwd.resolve(localPath.getFileName()).toString();
            Path remotePath = resolvedRemotePath(remotePathS);
            ProgressBar pb = new ProgressBar(new AtomicLong(0), new AtomicLong(1), remotePath, localPath.getFileName().toString());
            File file = localPath.toFile();
            long size = file.length();
            Consumer<Long> progressConsumer = bytesSoFar -> pb.update(writerForProgress, bytesSoFar, size);
            FileAsyncReader reader = new FileAsyncReader(file);
            Optional<ArchiveNavigator.Target> archive = archives.resolve(remotePath);
            if (archive.isPresent() && archive.get().isInArchive()) {
                putInArchive(archive.get(), localPath, size, writerForProgress);
                return "Added " + localPath + " to " + archive.get().fullPath();
            }
            boolean resumeUpload = cmd.flags.contains(Command.Flag.RESUME_UPLOAD.flag);
            peergosFileSystem.write(remotePath, reader, size, progressConsumer, resumeUpload);
            writerForProgress.println();
            writerForProgress.flush();
            return "Successfully uploaded " + localPath + " to remote " + remotePath;
        }
    }

    public String mkdir(ParsedCommand cmd) throws IOException {
        String remoteDirArg = cmd.firstArgument();
        Path remoteDirPath = cliContext.pwd.resolve(remoteDirArg);
        Optional<ArchiveNavigator.Target> archive = archives.resolve(remoteDirPath);
        if (archive.isPresent() && archive.get().isArchive())
            return "Directories in an archive are implied by the paths of the files in it, so there is nothing to create."
                    + " Put a file at " + remoteDirPath + "/<name> instead.";
        peergosFileSystem.mkdir(remoteDirPath);

        return "\nSuccessfully created " + remoteDirPath;
    }

    public String rm(ParsedCommand cmd) {
        if (!cmd.hasArguments())
            throw new IllegalStateException();

        Path remotePath = resolvedRemotePath(cmd.firstArgument()).toAbsolutePath().normalize();

        ArchiveNavigator.Target target = resolve(remotePath);
        if (target.isInArchive()) {
            ZipEntry entry = archives.entry(target);
            if (entry.isDirectory) {
                System.out.println("Delete " + remotePath + " and everything in it from the archive (Y/N)");
                String res = System.console().readLine().toLowerCase();
                if (! res.equals("y"))
                    return "Aborting delete";
            }
            // the bytes are overwritten in place, not just unlisted
            ZipWriter.remove(archiveFile(target), Collections.singletonList(entry.path), true,
                    cliContext.userContext.network, cliContext.userContext.crypto, x -> {}).join();
            archives.forget();
            return "Deleted " + remotePath + " from the archive";
        }

        Stat stat = target.stat;

        if (stat.fileProperties().isDirectory) {
            System.out.println("Delete directory and all contents of " + remotePath + " (Y/N)");
            String res = System.console().readLine().toLowerCase();
            if (! res.equals("y"))
                return "Aborting delete";
        }

        peergosFileSystem.delete(remotePath);
        return "Deleted " + remotePath;
    }

    public String mv(ParsedCommand cmd) {
        if (! cmd.hasSecondArgument())
            throw new IllegalStateException("Usage: " + Command.mv.example());
        Path source = resolvedRemotePath(cmd.firstArgument()).toAbsolutePath().normalize();
        String destinationArg = cmd.secondArgument();
        // a bare name renames where the file is, anything with a slash says where to put it
        Path destination = destinationArg.contains("/") ?
                resolvedRemotePath(destinationArg).toAbsolutePath().normalize() :
                source.getParent().resolve(destinationArg);

        ArchiveNavigator.Target target = resolve(source);
        if (target.isInArchive()) {
            ArchiveNavigator.Target to = archives.resolve(destination)
                    .orElseThrow(() -> new IllegalStateException("Could not find " + destination));
            if (! to.path.equals(target.path))
                throw new IllegalStateException("An entry can only be moved within its own archive."
                        + " Copy it out with get, then put it where you want it.");
            ZipWriter.moveEntry(archiveFile(target), target.entry, to.entry,
                    cliContext.userContext.network, cliContext.userContext.crypto, x -> {}).join();
            archives.forget();
            return "Moved " + source + " to " + destination;
        }

        FileWrapper file = cliContext.userContext.getByPath(source.toString()).join()
                .orElseThrow(() -> new IllegalStateException("Could not find " + source));
        FileWrapper parent = cliContext.userContext.getByPath(source.getParent().toString()).join()
                .orElseThrow(() -> new IllegalStateException("Could not find " + source.getParent()));
        if (destination.getParent().equals(source.getParent())) {
            file.rename(destination.getFileName().toString(), parent, source, cliContext.userContext).join();
            return "Renamed " + source + " to " + destination.getFileName();
        }
        FileWrapper targetDir = cliContext.userContext.getByPath(destination.getParent().toString()).join()
                .orElseThrow(() -> new IllegalStateException("Could not find " + destination.getParent()));
        file.moveTo(targetDir, parent, source, cliContext.userContext, () -> Futures.of(false)).join();
        if (! destination.getFileName().equals(source.getFileName())) {
            FileWrapper moved = cliContext.userContext.getByPath(destination.getParent().resolve(source.getFileName()).toString()).join()
                    .orElseThrow(() -> new IllegalStateException("Could not find the moved file"));
            FileWrapper movedParent = cliContext.userContext.getByPath(destination.getParent().toString()).join().get();
            moved.rename(destination.getFileName().toString(), movedParent,
                    destination.getParent().resolve(source.getFileName()), cliContext.userContext).join();
        }
        return "Moved " + source + " to " + destination;
    }

    public String exit(ParsedCommand cmd) {
        if (cmd.hasArguments())
            throw new IllegalStateException();
        this.isFinished = true;
        return "Exiting";

    }

    public String passwd(ParsedCommand cmd, Terminal terminal, LineReader reader) {
        terminal.writer().println("Enter current password:");
        String currentPassword = reader.readLine(PROMPT, PASSWORD_MASK);
        terminal.writer().println("Enter new  password:");
        String newPassword = reader.readLine(PROMPT, PASSWORD_MASK);
        try {
            cliContext.userContext.changePassword(currentPassword, newPassword, methods -> mfa(methods, terminal.writer(), reader)).join();
        } catch (Exception ex) {
            ex.printStackTrace();
            return "Failed to update password";
        }
        return "Password updated";
    }

    public String space(ParsedCommand cmd) {
        UserContext uc = cliContext.userContext;
        long spaceUsed = uc.getSpaceUsage(false).join();
        long spaceMB = spaceUsed / 1024 / 1024;
        return "Total space used: " + spaceMB + " MiB.";
    }

    public String getFollowRequests(ParsedCommand cmd) {

        List<FollowRequestWithCipherText> followRequests = cliContext.userContext.processFollowRequests().join();
        List<String> followRequestUsers = followRequests.stream()
                .map(e -> e.getEntry().ownerName)
                .collect(Collectors.toList());

        if (followRequests.isEmpty())
            return "No pending follow requests.";

        return followRequestUsers.stream()
                .collect(Collectors.joining("\n\t", "You have pending follow requests from the following users:\n", ""));
    }

    public String processFollowRequest(ParsedCommand cmd) {
        if (! cmd.hasArguments())
            return "Specify a user";
        if (! cmd.hasSecondArgument())
            return "Cannot process follow request - please specify one of "+ new ArrayList<>(Arrays.asList(Command.ProcessFollowRequestAction.values()));

        String userThatSentFollowRequest = cmd.firstArgument();
        Command.ProcessFollowRequestAction processFollowRequestAction = null;
        try {
            processFollowRequestAction = Command.ProcessFollowRequestAction.parse(cmd.secondArgument());
        } catch (IllegalArgumentException | NullPointerException ex) {
            return "Could not parse process-action '"+ cmd.secondArgument() +"' - please specify one of "+ new ArrayList<>(Arrays.asList(Command.ProcessFollowRequestAction.values()));
        }

        List<FollowRequestWithCipherText> followRequests = cliContext.userContext.processFollowRequests().join();
        Optional<FollowRequestWithCipherText> first = followRequests.stream()
                .filter(e -> userThatSentFollowRequest.equals(e.getEntry().ownerName))
                .findFirst();

        if (! first.isPresent())
            return "Could not process request from ' "+ userThatSentFollowRequest +"' - they haven't sent you a follow-request.";

        FollowRequestWithCipherText followRequestWithCipherText = first.get();
        boolean accept =  false;
        boolean reciprocate = false;

        switch (processFollowRequestAction) {
            case accept:
                accept = true;
                break;
            case accept_and_reciprocate:
                accept = true;
                reciprocate = true;
                break;
            case reject:
                accept = false;
                reciprocate = false;
                break;
            default:
                throw new IllegalStateException();
        }
        cliContext.userContext.sendReplyFollowRequest(followRequestWithCipherText, accept, reciprocate).join();
        return "Processed follow request from '"+ userThatSentFollowRequest +"' with "+ processFollowRequestAction +" action.";
    }


    public String shareReadAccess(ParsedCommand cmd) {
        return shareAccess(cmd, FileSystem.Permission.READ);
    }

    public String shareWriteAccess(ParsedCommand cmd) {
        return shareAccess(cmd, FileSystem.Permission.WRITE);
    }

    private String shareAccess(ParsedCommand cmd, FileSystem.Permission permission) {

        if (!cmd.hasSecondArgument())
            throw new IllegalStateException();

        String pathToShare = cmd.firstArgument();
        Path remotePath = resolvedRemotePath(pathToShare);

        Stat stat = checkPath(remotePath);
        boolean isDirectory = stat.fileProperties().isDirectory;
        String type = isDirectory ? "directory" : "file";
        String access = permission == FileSystem.Permission.READ ? "read" : "write";

        String userToGrantAccess = cmd.secondArgument();
        Set<String> followerUsernames = cliContext.userContext.getFollowerNames().join();
        if (!followerUsernames.contains(userToGrantAccess))
            return capitalise(type) + " not shared: specified-user " + userToGrantAccess + " is not following you";
        if (permission == FileSystem.Permission.WRITE && ! cliContext.username.equals(stat.user()))
            return capitalise(type) + " not shared: only the owner (" + stat.user() + ") can grant write access";
        try {
            peergosFileSystem.grant(remotePath, userToGrantAccess, permission);
        } catch (Exception ex) {
            ex.printStackTrace();
            return "Failed to share " + type + " '" + remotePath + "': " + errorMessage(ex);
        }
        return "Shared " + access + "-access to " + type + " '" + remotePath + "' with " + userToGrantAccess
                + (isDirectory ? ", including everything it contains." : "");
    }

    public String makePublic(ParsedCommand cmd) {

        if (! cmd.hasArguments())
            throw new IllegalStateException("Usage: " + Command.make_public.example());

        Path remotePath = resolvedRemotePath(cmd.firstArgument()).toAbsolutePath().normalize();

        Stat stat = checkPath(remotePath);
        boolean isDirectory = stat.fileProperties().isDirectory;
        String type = isDirectory ? "directory" : "file";

        if (! cliContext.username.equals(stat.user()))
            return capitalise(type) + " not published: only the owner (" + stat.user() + ") can make it public";
        try {
            FileWrapper file = cliContext.userContext.getByPath(remotePath).join()
                    .orElseThrow(() -> new IllegalStateException("Could not find " + remotePath));
            cliContext.userContext.makePublic(file).join();
        } catch (Exception ex) {
            ex.printStackTrace();
            return "Failed to publish " + type + " '" + remotePath + "': " + errorMessage(ex);
        }
        return "Made " + type + " '" + remotePath + "' public"
                + (isDirectory ? ", including everything it contains." : ".")
                + "\nAnyone can now read it at " + publicUrl(remotePath);
    }

    private String publicUrl(Path remotePath) {
        String server = cliContext.serverURL.endsWith("/") ?
                cliContext.serverURL.substring(0, cliContext.serverURL.length() - 1) :
                cliContext.serverURL;
        return server + "/public" + remotePath;
    }

    private static String capitalise(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** The message of the underlying failure, unwrapping the future's completion exception.
     */
    private static String errorMessage(Throwable t) {
        Throwable cause = t instanceof CompletionException && t.getCause() != null ? t.getCause() : t;
        return cause.getMessage() == null ? cause.toString() : cause.getMessage();
    }

    public String follow(ParsedCommand cmd) {
        if (!cmd.hasArguments())
            throw new IllegalStateException();

        String userToFollow = cmd.firstArgument();

        try {
            cliContext.userContext.sendInitialFollowRequest(userToFollow).join();
        } catch (Exception ex) {
            ex.printStackTrace();
            return "Failed to send follow request";
        }
        return "Sent follow request to '" + userToFollow + "'";
    }

    public String cd(ParsedCommand cmd) {
        String remotePathArg = cmd.hasArguments() ? cmd.firstArgument() : "";
        if (! cmd.hasArguments()) // no args goes to home
            cliContext.pwd = cliContext.home;
        Path remotePathToCdTo = resolvedRemotePath(remotePathArg).toAbsolutePath().normalize(); // normalize handles ".." etc.

        ArchiveNavigator.Target target = resolve(remotePathToCdTo);
        boolean isDirectory = target.isArchive() ?
                target.entry.isEmpty() || archives.entry(target).isDirectory :
                target.stat.fileProperties().isDirectory;
        if (! isDirectory)
            return "Specified path '" + remotePathToCdTo + "' is not a directory";
        cliContext.pwd = remotePathToCdTo;
        return "Current directory : " + remotePathToCdTo;
    }

    public String lcd(ParsedCommand cmd) {
        String localPathArg = cmd.hasArguments() ? cmd.firstArgument() : "";
        Path localPathToCdTo = resolveToPath(localPathArg).toAbsolutePath().normalize(); // normalize handles ".." etc.

        if (!localPathToCdTo.toFile().isDirectory())
            return "Specified path '" + localPathToCdTo + "' is not a directory";
        cliContext.lpwd = localPathToCdTo;
        return "Current local directory : " + localPathToCdTo;
    }

    public String pwd(ParsedCommand cmd) {
        return "Remote working directory: " + cliContext.pwd.toString();
    }

    public String lpwd(ParsedCommand cmd) {
        return "Local working directory: " + cliContext.lpwd.toString();
    }


    public String help(ParsedCommand cmd) {
        return formatHelp();
    }


    public String buildPrompt() {
        return new AttributedStringBuilder()
                .style(AttributedStyle.DEFAULT.background(AttributedStyle.BLACK).foreground(AttributedStyle.RED))
                .append(cliContext.username)
                .style(AttributedStyle.DEFAULT.background(AttributedStyle.BLACK))
                .append("@")
                .style(AttributedStyle.DEFAULT.background(AttributedStyle.BLACK).foreground(AttributedStyle.GREEN))
                .append(cliContext.serverURL)
                .style(AttributedStyle.DEFAULT)
                .append(" > ").toAnsi();
    }

    private List<String> localFilesLsFiles(String pathArgument, boolean filterDirs) {
        try {
            Path path = resolveToPath(pathArgument).toAbsolutePath();
            if (path.toFile().isFile() && !filterDirs)
                return Arrays.asList(path.toString());
            if (path.toFile().isDirectory() && filterDirs)
                return Arrays.asList(path.toString());
            if (path.toFile().isDirectory() && !filterDirs)
                return Files.list(path)
                        .map(Path::toString)
                        .collect(Collectors.toList());

            if (path.getParent().toFile().isDirectory())
                return Files.list(path.getParent())
                        .filter(p -> !filterDirs || p.toFile().isDirectory())
                        .map(Path::toString)
                        .collect(Collectors.toList());

        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
        return Collections.emptyList();
    }

    private List<String> listFollowers() {
        SocialState socialState = cliContext.userContext.getSocialState().join();
        return new ArrayList<>(socialState.followerRoots.keySet());
    }

    private List<String> listPendingFollowers() {
        SocialState socialState = cliContext.userContext.getSocialState().join();
        List<FollowRequestWithCipherText> pendingIncoming = socialState.pendingIncoming;
        return pendingIncoming.stream()
                .map(e -> e.req.entry.get().ownerName)
                .collect(Collectors.toList());
    }

    private List<String> listAllUsernames() {
        return cliContext.userContext.network.coreNode.getUsernames("").join();
    }

    /**
     *
     * @param pathArgument
     * @param filterDirs only return paths that are directories when true
     * @return
     */
    private List<String> remoteFilesLsFiles(String pathArgument, boolean filterDirs) {
        Path path = resolvedRemotePath(pathArgument).toAbsolutePath();
        Optional<ArchiveNavigator.Target> listable = listable(path);
        if (! listable.isPresent() && path.getParent() != null) // a partially typed name completes against its parent
            listable = listable(path.getParent());
        if (! listable.isPresent())
            return Collections.emptyList();

        ArchiveNavigator.Target target = listable.get();
        Stream<Path> children = target.isArchive() ?
                archives.open(target).listDirectory(target.entry).stream()
                        .filter(e -> (! filterDirs) || e.isDirectory)
                        .map(e -> target.path.resolve(e.path)) :
                peergosFileSystem.ls(target.path, false)
                        .stream()
                        .filter(p -> (! filterDirs) || checkPath(p).fileProperties().isDirectory);
        return children
                .map(p -> p.isAbsolute() ? cliContext.pwd.relativize(p): p)
                .map(Path::toString)
                .collect(Collectors.toList());
    }

    /** A path whose children are completion candidates: a directory, an archive, or a directory in one.
     */
    private Optional<ArchiveNavigator.Target> listable(Path path) {
        Optional<ArchiveNavigator.Target> resolved = archives.resolve(path);
        if (! resolved.isPresent())
            return resolved;
        ArchiveNavigator.Target target = resolved.get();
        if (target.isArchive())
            return target.entry.isEmpty() || archives.open(target).getIndex().isDirectory(target.entry) ?
                    resolved :
                    Optional.empty();
        return target.stat.fileProperties().isDirectory ? resolved : Optional.empty();
    }
    /**
     * Build the command completer.
     *
     * @return
     */
    private Completer getCompleter(Command.Argument arg) {
        switch (arg) {
            case REMOTE_FILE:
                return remoteFilesCompleter;
            case REMOTE_DIR:
                return remoteDirsCompleter;
            case LOCAL_FILE:
                return localFilesCompleter;
            case LOCAL_DIR:
                return localDirsCompleter;
            case USERNAME:
                return allUsernamesCompleter;
            case FOLLOWER:
                return followersCompleter;
            case PENDING_FOLLOW_REQUEST:
                return pendingFollowersCompleter;
            case PROCESS_FOLLOW_REQUEST:
                return processFollowRequestCompleter;
            default:
                throw new IllegalStateException();
        }
    }

    private Completer buildCompletionNode(Command cmd) {
        if (cmd.secondArg != null) {
            Completer arg1 = getCompleter(cmd.firstArg);
            Completer arg2 = getCompleter(cmd.secondArg);
            return new ArgumentCompleter(
                    new StringsCompleter(cmd.name()),
                    new Completers.OptionCompleter(List.of(arg1, arg2, NullCompleter.INSTANCE),
                            cmd.flags.stream()
                            .map(f -> new Completers.OptDesc(f.flag, f.flag))
                            .collect(Collectors.toList()), 1)
            );
        }
        else if (cmd.firstArg != null && ! cmd.flags.isEmpty()) {
            return new ArgumentCompleter(
                    new StringsCompleter(cmd.name()),
                    new Completers.OptionCompleter(List.of(getCompleter(cmd.firstArg), NullCompleter.INSTANCE),
                            cmd.flags.stream()
                                    .map(f -> new Completers.OptDesc(f.flag, f.flag))
                                    .collect(Collectors.toList()), 1)
            );
        }
        else if (cmd.firstArg !=  null) {
            return new ArgumentCompleter(
                    new StringsCompleter(cmd.name()), getCompleter(cmd.firstArg));
        }
        else
            return new ArgumentCompleter(new StringsCompleter(cmd.name()));
    }

    public Completer buildCompleter() {

        List<Completer> cmds = Stream.of(Command.values())
                .map(this::buildCompletionNode)
                .collect(Collectors.toList());

        return new AggregateCompleter(cmds);
    }

    /**
     * Build a CLIContext from the CLI - from user interaction.
     *
     * @return
     */

    public static CLIContext buildContextFromCLI(Args args) {
        Terminal terminal = buildTerminal();

        DefaultParser parser = new DefaultParser();
        LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .parser(parser)
                .completer(new StringsCompleter(
                        "http://",
                        "https://",
                        "https://peergos.net",
                        "http://localhost:8000"))
                .build();

        String address = args.hasArg("peergos-url") ?
                args.getArg("peergos-url") :
                reader.readLine("Enter Server address \n > ").trim();
        URL serverURL = null;

        final PrintWriter writer = terminal.writer();
        try {
            serverURL = new URL(address);
        } catch (MalformedURLException ex) {
            writer.println("Specified server " + address + " is not valid!");
            writer.flush();
            System.exit(1);
        }

        String username = args.hasArg("username") ?
                args.getArg("username") :
                reader.readLine("Enter username" + PROMPT).trim();

        Optional<ProxySelector> proxy = ProxyChooser.build(args);
        NetworkAccess network = Builder.buildJavaNetworkAccess(serverURL, address.startsWith("https"), Optional.of("Peergos-" + UserService.CURRENT_VERSION + "-shell"), proxy).join();
        Consumer<String> progressConsumer =  msg -> {
            writer.println(msg);
            writer.flush();
            return;
        };

        boolean isRegistered = network.isUsernameRegistered(username).join();
        if (! isRegistered) {
            String password = Passwords.generate();
            writer.println("Generated password: " + password);
            writer.println("Re-enter password");
            String password2 = reader.readLine(PROMPT, PASSWORD_MASK);
            if (! password.equals(password2)) {
                writer.println("Passwords don't match!");
                System.exit(0);
            }
            writer.println("Enter a signup token, or press enter if this server doesn't require one.");
            writer.println("(An admin of the server can create one with: java -jar Peergos.jar quota token create)");
            String token = reader.readLine(PROMPT).trim();;

            UserContext userContext = UserContext.signUp(username, password, token, Optional.empty(), s -> {},
                    Optional.empty(), network, CRYPTO, progressConsumer).join();
            return new CLIContext(terminal, userContext, serverURL.toString(), username);
        } else {
            String password = args.hasArg("PEERGOS_PASSWORD") ?
                    args.getArg("PEERGOS_PASSWORD") :
                    reader.readLine("Enter password for '" + username + "'" + PROMPT, PASSWORD_MASK);
            UserContext userContext = UserContext.signIn(username, password,
                    methods -> mfa(methods, writer, reader), false, false, network, CRYPTO, progressConsumer).join();
            return new CLIContext(terminal, userContext, serverURL.toString(), username);
        }
    }

    private static CompletableFuture<MultiFactorAuthResponse> mfa(MultiFactorAuthRequest req,
                                                                  PrintWriter writer,
                                                                  LineReader reader) {
        Optional<MultiFactorAuthMethod> anyTotp = req.methods.stream().filter(m -> m.type == MultiFactorAuthMethod.Type.TOTP).findFirst();
        if (anyTotp.isEmpty())
            throw new IllegalStateException("No supported 2 factor auth method! " + req.methods);
        MultiFactorAuthMethod totp = anyTotp.get();
        writer.println("Enter TOTP code for login");
        String code = reader.readLine(PROMPT).trim();
        return Futures.of(new MultiFactorAuthResponse(totp.credentialId, Either.a(code)));
    }

    public static Terminal buildTerminal() {
        try {
            return TerminalBuilder.builder()
                    .system(true)
                    .build();
        } catch (IOException ioe) {
            throw new IllegalStateException(ioe);
        }

    }

    @Override
    public void run() {
        DefaultParser parser = new DefaultParser();
        LineReader reader = LineReaderBuilder.builder()
                .terminal(cliContext.terminal)
                .completer(buildCompleter())
                .parser(parser)
                .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
//                .variable(LineReader.SECONDARY_PROMPT_PATTERN, "%M%P > ")
                .build();
        boolean color = true;

        while (!isFinished) {
            while (!isFinished) {
                String line = null;
                try {
                    line = reader.readLine(buildPrompt(), null, (MaskingCallback) null, null);
                } catch (UserInterruptException e) {
                    // Ignore
                } catch (EndOfFileException e) {
                    return;
                }
                if (line == null) {
                    continue;
                }

                ParsedCommand parsedCommand = null;
                try {
                    parsedCommand = fromLine(line);
                } catch (Exception ex) {
                    System.out.println("Could not parse command.");
                    continue;
                }

                String response = handle(parsedCommand, cliContext.terminal, reader);
//                if (color) {
//                    terminal.writer().println(
//                            AttributedString.fromAnsi("\u001B[0m\"" + response + "\"")
//                                    .toAnsi(terminal));
//
//                } else {
                cliContext.terminal.writer().println(response);
//                }
                cliContext.terminal.flush();
            }
        }
    }

    private static Crypto CRYPTO;

    public static void buildAndRun(Args args) {
        CRYPTO = Main.initCrypto();
        PublicSigningKey.addProvider(PublicSigningKey.Type.Ed25519, CRYPTO.signer);
        disableLogSpam();
        JvmThumbnailer.initJava();
        JavaInflate.init();
        Logging.LOG().setLevel(Level.WARNING);
        CLIContext cliContext = buildContextFromCLI(args);
        new CLI(cliContext).run();
    }
}
