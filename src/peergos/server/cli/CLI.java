package peergos.server.cli;

import org.jline.builtins.*;
import org.jline.reader.*;
import org.jline.reader.impl.*;
import org.jline.reader.impl.completer.StringsCompleter;
import org.jline.terminal.*;
import org.jline.utils.*;

import peergos.server.*;
import peergos.server.simulation.*;
import peergos.server.simulation.FileSystem;
import peergos.server.user.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.login.mfa.*;
import peergos.shared.social.FollowRequestWithCipherText;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.util.logging.Level;
import java.util.stream.*;

import static org.jline.builtins.Completers.TreeCompleter.node;

public class CLI implements Runnable {

    private final CLIContext cliContext;
    private final FileSystem peergosFileSystem;
    private final ListFilesCompleter remoteFilesCompleter, localFilesCompleter, remoteDirsCompleter;
    private final Completer allUsernamesCompleter, followersCompleter, pendingFollowersCompleter, processFollowRequestCompleter;
    private volatile boolean isFinished;

    public CLI(CLIContext cliContext) {
        this.cliContext = cliContext;
        this.peergosFileSystem = new PeergosFileSystemImpl(cliContext.userContext);
        this.remoteFilesCompleter = new ListFilesCompleter(path -> this.remoteFilesLsFiles(path, false));
        this.remoteDirsCompleter = new ListFilesCompleter(path -> this.remoteFilesLsFiles(path, true));
        this.localFilesCompleter = new ListFilesCompleter(this::localFilesLsFiles);
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
        return resolveToPath(arg, Paths.get(""));
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
                case get:  // download
                    return get(parsedCommand, terminal.writer());
                case put:  //upload
                    return put(parsedCommand, terminal.writer());
                case rm:
                    return rm(parsedCommand);
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
                case cd:
                    return cd(parsedCommand);
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

        Stat stat = checkPath(path);
        if (stat.fileProperties().isDirectory)
            return peergosFileSystem.ls(path, false).stream()
                .map(Path::toString)
                .collect(Collectors.joining("\n"));

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

        Stat stat = checkPath(remotePath);
        // TODO
        if (stat.fileProperties().isDirectory)
            throw new IllegalStateException("Directory is not supported");

        String localPathArg = cmd.hasSecondArgument() ? cmd.secondArgument() : "";
        Path localPath = resolveToPath(localPathArg).toAbsolutePath();

        if (localPath.toFile().isDirectory())
            localPath = localPath.resolve(stat.fileProperties().name);
        else if (!localPath.toFile().getParentFile().isDirectory())
            throw new IllegalStateException("Specified local path '" + localPath.getParent() + "' is not a directory or does not exist.");

        ProgressBar pb = new ProgressBar(new AtomicLong(0), new AtomicLong(1), remotePath.getParent(), remotePath.getFileName().toString());
        BiConsumer<Long, Long> progressConsumer = (bytes, size) -> pb.update(writerForProgress, bytes, size);

        byte[] data = peergosFileSystem.read(remotePath, progressConsumer);
        writerForProgress.println();
        writerForProgress.flush();

        Files.write(localPath, data);

        return "Downloaded " + remotePath + " to " + localPath;
    }

    private List<String> convert(Path p) {
        List<String> res = new ArrayList<>();
        for (int i=0; i < p.getNameCount(); i++)
            res.add(p.getName(i).toString());
        return res;
    }

    private AsyncReader reader(File f) {
        try {
            return new FileAsyncReader(f);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private interface ProgressCreator {
        ProgressConsumer<Long> create(Path remoteRelativeDir, String filename, Long size);
    }

    private Stream<FileWrapper.FolderUploadProperties> parseLocalFolder(Path remoteRelativeDir,
                                                                        Path localDir,
                                                                        boolean skipExisting,
                                                                        AtomicLong fileCount,
                                                                        ProgressCreator progressCreator) {
        try {
            List<FileWrapper.FileUploadProperties> files = Files.list(localDir).filter(p -> p.toFile().isFile())
                    .map(p -> new FileWrapper.FileUploadProperties(p.getFileName().toString(), reader(p.toFile()),
                            (int) (p.toFile().length() >> 32), (int) p.toFile().length(), skipExisting, true,
                            progressCreator.create(remoteRelativeDir, p.getFileName().toString(), p.toFile().length())))
                    .collect(Collectors.toList());
            fileCount.addAndGet(files.size());
            FileWrapper.FolderUploadProperties dir = new FileWrapper.FolderUploadProperties(convert(remoteRelativeDir), files);
            return Stream.concat(Stream.of(dir),
                    Files.list(localDir)
                            .filter(p -> p.toFile().isDirectory())
                            .flatMap(p -> parseLocalFolder(remoteRelativeDir.resolve(p.getFileName()), localDir.resolve(p.getFileName()), skipExisting, fileCount, progressCreator)));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public String put(ParsedCommand cmd, PrintWriter writerForProgress) throws IOException {
        String localPathArg = cmd.firstArgument();
        Path localPath = resolveToPath(localPathArg).toAbsolutePath().normalize();

        if (localPath.toFile().isDirectory()) {
            Path remotePath = cmd.hasSecondArgument() ? cliContext.pwd.resolve(Paths.get(cmd.secondArgument())) : cliContext.pwd;
            boolean skipExisting = cmd.hasThirdArgument() && cmd.thirdArgument().equalsIgnoreCase("true");
            AtomicLong fileCount = new AtomicLong(0);
            AtomicLong doneFiles = new AtomicLong(0);
            peergosFileSystem.writeSubtree(remotePath, parseLocalFolder(localPath.getFileName(), localPath, skipExisting, fileCount,
                    (path, name, size) -> {
                        ProgressBar pb = new ProgressBar(doneFiles, fileCount, path, name);
                        return bytesWritten -> pb.update(writerForProgress, bytesWritten, size);
                    }), f -> Futures.of(true));
            return "\nSuccessfully uploaded " + localPath + " to remote " + remotePath;
        } else {
            String remotePathS = cmd.hasSecondArgument() ? cmd.secondArgument() : cliContext.pwd.resolve(localPath.getFileName()).toString();
            Path remotePath = resolvedRemotePath(remotePathS);
            byte[] data = Files.readAllBytes(localPath);
            ProgressBar pb = new ProgressBar(new AtomicLong(0), new AtomicLong(1), remotePath, localPath.getFileName().toString());
            Consumer<Long> progressConsumer = bytesSoFar -> pb.update(writerForProgress, bytesSoFar, data.length);
            peergosFileSystem.write(remotePath, data, progressConsumer);
            writerForProgress.println();
            writerForProgress.flush();
            return "Successfully uploaded " + localPath + " to remote " + remotePath;
        }
    }

    public String rm(ParsedCommand cmd) {
        if (!cmd.hasArguments())
            throw new IllegalStateException();

        Path remotePath = resolvedRemotePath(cmd.firstArgument()).toAbsolutePath().normalize();

        Stat stat;
        try {
            stat = peergosFileSystem.stat(remotePath);
        } catch (Exception ex) {
            throw new IllegalStateException("Could not find remote specified remote path '" + remotePath + "'", ex);
        }

        if (stat.fileProperties().isDirectory) {
            System.out.println("Delete directory and all contents of " + remotePath + " (Y/N)");
            String res = System.console().readLine().toLowerCase();
            if (! res.equals("y"))
                return "Aborting delete";
        }

        peergosFileSystem.delete(remotePath);
        return "Deleted " + remotePath;
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
        long spaceUsed = uc.getSpaceUsage().join();
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

        if (!cmd.hasSecondArgument())
            throw new IllegalStateException();

        String pathToShare = cmd.firstArgument();
        Path remotePath = resolvedRemotePath(pathToShare);

        Stat stat = checkPath(remotePath);
        // TODO
        if (stat.fileProperties().isDirectory)
            throw new IllegalStateException("Directory is not supported");

        String userToGrantReadAccess = cmd.secondArgument();
        Set<String> followerUsernames = cliContext.userContext.getFollowerNames().join();
        if (!followerUsernames.contains(userToGrantReadAccess))
            return "File not shared: specified-user " + userToGrantReadAccess + " is not following you";
        try {
            cliContext.userContext.shareReadAccessWith(remotePath, new HashSet<>(Arrays.asList(userToGrantReadAccess))).join();
        } catch (Exception ex) {
            ex.printStackTrace();
            return "Failed not share file";
        }
        return "Shared read-access to '" + remotePath + "' with " + userToGrantReadAccess;
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
        Path remotePathToCdTo = resolvedRemotePath(remotePathArg).toAbsolutePath().normalize(); // normalize handles ".." etc.

        Stat stat = checkPath(remotePathToCdTo);
        if (!stat.fileProperties().isDirectory)
            return "Specified path '" + remotePathToCdTo + "' is not a directory";
        cliContext.pwd = remotePathToCdTo;
        return "Current directory : " + remotePathToCdTo;
    }

    public String pwd(ParsedCommand cmd) {
        return "Remote working directory: " + cliContext.pwd.toString();
    }

    public String lpwd(ParsedCommand cmd) {
        return "Local working directory: " + System.getProperty("user.dir");
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

    private List<String> localFilesLsFiles(String pathArgument) {
        try {
            Path path = resolveToPath(pathArgument).toAbsolutePath();
            if (path.toFile().isFile())
                return Arrays.asList(path.toString());
            if (path.toFile().isDirectory())
                return Files.list(path)
                        .map(Path::toString)
                        .collect(Collectors.toList());

            if (!path.getParent().toFile().isDirectory())
                return Files.list(path.getParent())
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
        Stat stat = null;
        try {
            stat = peergosFileSystem.stat(path);
            if (! stat.fileProperties().isDirectory)
                throw new Exception();
        } catch (Exception ex) {
            //try parent
            path = path.getParent();
            try {
                peergosFileSystem.stat(path);
            } catch (Exception ex2) {
                return Collections.emptyList();
            }

        }
        final Path parentPath = path;
        List<String> completeOptions = peergosFileSystem.ls(parentPath, false)
                .stream()
                .filter(p -> (! filterDirs) || checkPath(p).fileProperties().isDirectory)
                .map(p -> p.isAbsolute() ? cliContext.pwd.relativize(p): p)
                .map(Path::toString)
                .collect(Collectors.toList());
        return completeOptions;
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

    private Completers.TreeCompleter.Node buildCompletionNode(Command cmd) {
        if (cmd.secondArg  != null) {
            return node(cmd.name(),
                        node(getCompleter(cmd.firstArg),
                                node(getCompleter(cmd.secondArg))));

        }
        else if (cmd.firstArg !=  null) {
            return node(cmd.name(),
                    node(getCompleter(cmd.firstArg)));
        }
        else
            return node(cmd.name());

    }

    public Completer buildCompleter() {

        List<Completers.TreeCompleter.Node> nodes = Stream.of(Command.values())
                .map(this::buildCompletionNode)
                .collect(Collectors.toList());

        return new Completers.TreeCompleter(nodes);
    }

    /**
     * Build a CLIContext from the CLI - from user interaction.
     *
     * @return
     */

    public static CLIContext buildContextFromCLI() {
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

        String address = reader.readLine("Enter Server address \n > ").trim();
        URL serverURL = null;

        final PrintWriter writer = terminal.writer();
        try {
            serverURL = new URL(address);
        } catch (MalformedURLException ex) {
            writer.println("Specified server " + address + " is not valid!");
            writer.flush();
            return buildContextFromCLI();
        }

        writer.println("Enter username");
        String username = reader.readLine(PROMPT).trim();

        NetworkAccess network = Builder.buildJavaNetworkAccess(serverURL, ! serverURL.getHost().equals("localhost")).join();
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
            writer.println("Enter any signup token (or press enter if none):");
            String token = reader.readLine(PROMPT).trim();;

            UserContext userContext = UserContext.signUp(username, password, token, Optional.empty(), s -> {},
                    Optional.empty(), network, CRYPTO, progressConsumer).join();
            return new CLIContext(userContext, serverURL.toString(), username);
        } else {
            writer.println("Enter password for '" + username + "'");
            String password = reader.readLine(PROMPT, PASSWORD_MASK);
            UserContext userContext = UserContext.signIn(username, password,
                    methods -> mfa(methods, writer, reader), network, CRYPTO, progressConsumer).join();
            return new CLIContext(userContext, serverURL.toString(), username);
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

        Terminal terminal = buildTerminal();
        DefaultParser parser = new DefaultParser();
        LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .completer(buildCompleter())
                .parser(parser)
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

                String response = handle(parsedCommand, terminal, reader);
//                if (color) {
//                    terminal.writer().println(
//                            AttributedString.fromAnsi("\u001B[0m\"" + response + "\"")
//                                    .toAnsi(terminal));
//
//                } else {
                terminal.writer().println(response);
//                }
                terminal.flush();
            }
        }
    }

    private static Crypto CRYPTO;

    public static void main(String[] args) {
        CRYPTO = Main.initCrypto();
        ThumbnailGenerator.setInstance(new JavaImageThumbnailer());
        Logging.LOG().setLevel(Level.WARNING);
        CLIContext cliContext = buildContextFromCLI();
        new CLI(cliContext).run();
    }
}
