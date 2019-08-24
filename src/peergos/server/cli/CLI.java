package peergos.server.cli;

import org.jline.builtins.*;
import org.jline.reader.*;
import org.jline.reader.impl.*;
import org.jline.reader.impl.completer.StringsCompleter;
import org.jline.terminal.*;
import org.jline.utils.*;

import peergos.server.simulation.*;
import peergos.server.simulation.FileSystem;
import peergos.server.util.Logging;
import peergos.shared.*;
import peergos.shared.social.FollowRequestWithCipherText;
import peergos.shared.user.UserContext;

import java.io.IOException;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Level;
import java.util.stream.*;

import static org.jline.builtins.Completers.TreeCompleter.node;

public class CLI implements Runnable {

    private final CLIContext cliContext;
    private final FileSystem peergosFileSystem;
    private final RemoteFilesCompleter remoteFilesCompleter;
    private volatile boolean isFinished;

    public CLI(CLIContext cliContext) {
        this.cliContext = cliContext;
        this.peergosFileSystem = new PeergosFileSystemImpl(cliContext.userContext);
        this.remoteFilesCompleter = new RemoteFilesCompleter(this::lsForRemoteFilesCompleter);
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

        return sb.toString();
    }

    private String handle(ParsedCommand parsedCommand, Terminal terminal, LineReader reader) {


        try {
            switch (parsedCommand.cmd) {
                case ls:
                    return ls(parsedCommand);
                case get:  // download
                    return get(parsedCommand);
                case put:  //upload
                    return put(parsedCommand);
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
            return peergosFileSystem.ls(path).stream()
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

    public String get(ParsedCommand cmd) throws IOException {
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

        byte[] data = peergosFileSystem.read(remotePath);
        Files.write(localPath, data);
        return "Downloaded " + remotePath + " to " + localPath;
    }

    public String put(ParsedCommand cmd) throws IOException {
        String localPathArg = cmd.firstArgument();
        Path localPath = resolveToPath(localPathArg).toAbsolutePath().normalize();

        // TODO
        if (localPath.toFile().isDirectory())
            throw new IllegalStateException("Cannot upload directory: this is not supported");

        if (!localPath.toFile().isFile())
            throw new IllegalStateException("Could not find specified local file '" + localPath + "'");


        String remotePathS = cmd.hasSecondArgument() ? cmd.secondArgument() : cliContext.pwd.resolve(localPath.getFileName()).toString();
        Path remotePath = resolvedRemotePath(remotePathS);

        byte[] data = Files.readAllBytes(localPath);
        peergosFileSystem.write(remotePath, data);
        return "Successfully uploaded " + localPath + " to remote " + remotePath;
    }

    public String rm(ParsedCommand cmd) {
        if (!cmd.hasArguments())
            throw new IllegalStateException();

        Path remotePath = resolvedRemotePath(cmd.firstArgument()).toAbsolutePath().normalize();

        Stat stat = null;
        try {
            stat = peergosFileSystem.stat(remotePath);
        } catch (Exception ex) {
            throw new IllegalStateException("Could not find remote specified remote path '" + remotePath + "'", ex);
        }

        // TODO
        if (stat.fileProperties().isDirectory)
            throw new IllegalStateException("Cannot remove directory '" + remotePath + "': directory removal not yet supported");

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
            cliContext.userContext.changePassword(currentPassword, newPassword).join();
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

    private List<String> lsForRemoteFilesCompleter(String pathArgument) {
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
                System.out.print( ",returning empty on "+ path);
                return Collections.emptyList();
            }

        }
        final Path parentPath = path;
        List<String> completeOptions = peergosFileSystem.ls(parentPath)
                .stream()
                .map(p -> {
                    if  (! p.isAbsolute())
                        return cliContext.pwd.relativize(p);
                    return p;
                })
                .map(Path::toString)
                .collect(Collectors.toList());
        return completeOptions;
    }
    /**
     * Build the command completer.
     *
     * @return
     */
    private Completers.TreeCompleter.Node buildCompletionNode(Command cmd) {
        List<Object> nodesForCmd = new ArrayList<>();

        nodesForCmd.add(cmd.name());

        if (cmd.hasRemoteFileFirstArg())
            nodesForCmd.add(node(remoteFilesCompleter));

        return node(nodesForCmd.toArray(new Object[0]));
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
                        "https://demo.peergos.net",
                        "https://alpha.peergos.net",
                        "http://localhost"))
                .build();

        String address = reader.readLine("Enter Server address \n > ").trim();
        URL serverURL = null;

        try {
            serverURL = new URL(address);
        } catch (MalformedURLException ex) {
            terminal.writer().println("Specified server " + address + " is not valid!");
            terminal.writer().flush();
            return buildContextFromCLI();
        }

        terminal.writer().println("Enter username");
        String username = reader.readLine(PROMPT).trim();

        terminal.writer().println("Enter password for '" + username + "'");
        String password = reader.readLine(PROMPT, PASSWORD_MASK);

        NetworkAccess networkAccess = NetworkAccess.buildJava(serverURL).join();

        UserContext userContext = UserContext.signIn(username, password, networkAccess, CRYPTO).join();
        return new CLIContext(userContext, serverURL.toString(), username);
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


                ParsedCommand parsedCommand = fromLine(line);
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


    public static void main(String[] args) throws Exception {
        CRYPTO = Crypto.initJava();

        Logging.LOG().setLevel(Level.WARNING);

        CLIContext cliContext = buildContextFromCLI();
        new CLI(cliContext).run();
    }
}
