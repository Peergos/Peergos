package peergos.server.cli;

import org.jline.builtins.Completers;
import org.jline.reader.*;
import org.jline.reader.impl.DefaultParser;
import org.jline.reader.impl.completer.StringsCompleter;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import peergos.server.tests.simulation.FileSystem;
import peergos.server.tests.simulation.PeergosFileSystemImpl;
import peergos.server.tests.simulation.Stat;
import peergos.server.util.Logging;
import peergos.shared.Crypto;
import peergos.shared.NetworkAccess;
import peergos.shared.user.UserContext;
import peergos.shared.util.Pair;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.jline.builtins.Completers.TreeCompleter.node;

public class CLI implements Runnable {
    public enum Command {
        get,
        put,
        ls,
        rm,
        exit,
        quit,
        help,
        space,
        get_follow_requests,
        follow,
        share,
        passwd;

        public static Set<Command> ALLOWED = Stream.of(Command.values())
                .collect(Collectors.toSet());

        public static Command parse(String cmd) {
            try {
                return Command.valueOf(cmd);
            } catch (IllegalStateException | NullPointerException ex) {
                throw new IllegalStateException("Specified command "+  cmd +" is not a valid command : " + ALLOWED);
            }
        }
    }

    private static class ParsedCommand {
        public final Command cmd;
        public final String line;
        public final List<String> arguments;

        private ParsedCommand(Command cmd, String line, List<String> arguments) {
            this.cmd = cmd;
            this.line = line;
            this.arguments = new ArrayList<>(arguments); // words without the cmd
        }

        public boolean hasArguments() {
            return !  arguments.isEmpty();
        }

        public boolean hasSecondArgument() {
            return arguments.size() > 1;
        }

        public String firstArgument() {
            if (arguments.size() < 1)
                throw new IllegalStateException("Specifed command "+ line +" requires an argument");
            return arguments.get(0);
        }

        public String secondArgument() {
            if (arguments.size() < 2)
                throw new IllegalStateException("Specifed command "+ line +" requires a second argument");
            return arguments.get(1);
        }

        @Override
        public String toString() {
            return "ParsedCommand{" +
                    "cmd=" + cmd +
                    ", line='" + line + '\'' +
                    ", arguments=" + arguments +
                    '}';
        }
    }

    /**
     * resolve against remote pwd if path is relative
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
        return pathToResolveTo.resolve(p);
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

    private static final Map<String, String> CMD_TO_HELP = new HashMap<>();
    static {
        CMD_TO_HELP.put(Command.ls.toString(),"ls <path>. List contents of a remote directory.");
        CMD_TO_HELP.put(Command.get.toString(),"get remote-path <local path>. Download a file.");
        CMD_TO_HELP.put(Command.put.toString(),"put local-path remote-path. Upload a file.");
        CMD_TO_HELP.put(Command.rm.toString(),"rm remote-path. Remove a remote-file.");
        CMD_TO_HELP.put(Command.exit.toString(),"exit. Disconnect.");
        CMD_TO_HELP.put(Command.quit.toString(),"quit. Disconnect.");
        CMD_TO_HELP.put(Command.help.toString(),"help. Show this help.");
    }

    static String formatHelp() {
        StringBuilder sb = new StringBuilder();
        sb.append("Available commands:").append("\n");
        for (Map.Entry<String, String> entry : CMD_TO_HELP.entrySet()) {
            String cmd = entry.getKey();
            String help = entry.getValue();
            sb.append(cmd).append("\t\t").append(help).append("\n");
        }

        return sb.toString();
    }

    private String handle(ParsedCommand parsedCommand) {


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
                    return exit(parsedCommand);
                case help:
                    return help(parsedCommand);
//                case space:
//                case get_follow_requests:
//                case follow:
//                case share:
//                case passwd:
                default:
                    return "Unexpected cmd '" + parsedCommand.cmd + "'";
            }
        } catch (Exception ex) {
            ex.printStackTrace();
            return "Failed to execute "+ parsedCommand;

        }

    }

    private final CLIContext cliContext;
    private final FileSystem peergosFileSystem;
    private boolean isFinished;

    public String ls(ParsedCommand cmd) {

        Path path = cmd.hasArguments() ? Paths.get(cmd.firstArgument()) : cliContext.pwd;
        List<Path> children = peergosFileSystem.ls(path);

        return children.stream()
                .map(Path::toString)
                .collect(Collectors.joining("\n"));
    }

    public String get(ParsedCommand cmd) throws IOException {
        if (! cmd.hasArguments())
            throw new IllegalStateException();

        Path remotePath = resolvedRemotePath(cmd.firstArgument());

        Stat stat = null;
        try {
            stat = peergosFileSystem.stat(remotePath);
        }  catch (Exception ex) {
            throw new IllegalStateException("Could not find remote specified remote path '"+ remotePath + "'",  ex);
        }

        // TODO
        if (stat.fileProperties().isDirectory)
            throw new IllegalStateException("Directory is not supported");

        String localPathArg = cmd.hasSecondArgument() ? cmd.secondArgument() : "";
        Path localPath = resolveToPath(localPathArg).toAbsolutePath();


        if (localPath.toFile().isDirectory())
            localPath = localPath.resolve(stat.fileProperties().name);
        else if (! localPath.toFile().getParentFile().isDirectory())
            throw new IllegalStateException("Specified local path '"+ localPath.getParent() + "' is not a directory or does not exist.");

        byte[] data = peergosFileSystem.read(remotePath);
        Files.write(localPath, data);
        return "Downloaded "+ remotePath +" to " + localPath;
    }

    public String put(ParsedCommand cmd) throws IOException {
        String localPathArg = cmd.firstArgument();
        Path localPath = resolveToPath(localPathArg);

        // TODO
        if (localPath.toFile().isDirectory())
            throw new IllegalStateException("Cannot upload directory: this is not supported");

        if (! localPath.toFile().isFile())
            throw new IllegalStateException("Could not find specified local file '" + localPath +"'");


        String remotePathS = cmd.hasSecondArgument() ? cmd.secondArgument() : cliContext.pwd.resolve(localPath.getFileName()).toString();
        Path remotePath = resolvedRemotePath(remotePathS);

        byte[] data = Files.readAllBytes(localPath);
        peergosFileSystem.write(remotePath, data);
        return "Successfully uploaded " + localPath +" to remote "+ remotePath;
    }

    public String rm(ParsedCommand cmd) {
        if (! cmd.hasArguments())
            throw new IllegalStateException();

        Path remotePath = resolvedRemotePath(cmd.firstArgument());

        Stat stat = null;
        try {
            stat = peergosFileSystem.stat(remotePath);
        }  catch (Exception ex) {
            throw new IllegalStateException("Could not find remote specified remote path '"+ remotePath + "'",  ex);
        }

        // TODO
        if (stat.fileProperties().isDirectory)
            throw new IllegalStateException("Cannot remove directory '"+  remotePath +"': directory removal not yet supported");

        peergosFileSystem.delete(remotePath);
        return "Deleted "+ remotePath;
    }

    public String exit(ParsedCommand cmd) {
        if (cmd.hasArguments())
            throw new IllegalStateException();
        this.isFinished = true;
        return "Exiting";
    }

    public String help(ParsedCommand cmd) {
        return formatHelp();
    }
    
    public CLI(CLIContext cliContext) {
        this.cliContext = cliContext;
        this.peergosFileSystem = new PeergosFileSystemImpl(cliContext.userContext);
    }

    public static class CLIContext {
        private final UserContext userContext;
        private final String serverURL, username;
        private Path pwd;

        public CLIContext(UserContext userContext, String serverURL, String username) {
            this.userContext = userContext;
            this.serverURL = serverURL;
            this.username = username;
            this.pwd = Paths.get("/"+username);
        }
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

    /**
     * Build the command completer.
     * @return
     */
    public Completer buildCompleter() {

        List<Completers.TreeCompleter.Node> nodes = CMD_TO_HELP.keySet().stream()
                .map(cmd -> node(cmd))
                .collect(Collectors.toList());

        return new Completers.TreeCompleter(nodes);
    }

    /**
     * Build a CLIContext from the CLI - from user interaction.
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
            return buildContextFromCLI();
        }

        terminal.writer().println("Enter username");
        String username = reader.readLine(PROMPT).trim();

        terminal.writer().println("Enter password for '" + username + "'");
        String password = reader.readLine(PROMPT, PASSWORD_MASK).trim();

        NetworkAccess networkAccess = NetworkAccess.buildJava(serverURL).join();

        UserContext userContext = UserContext.signIn(username, password, networkAccess, CRYPTO).join();
        return new CLIContext(userContext, serverURL.toString(), username);
    }

    public CLIContext buildCliContext() {
        // create terminal
        //
        return buildContextFromCLI();
//        MOCK
//        return new CLIContext(null, "server", "user");
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

        while (! isFinished) {
            while (! isFinished) {
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
                String response = handle(parsedCommand);
//                if (color) {
//                    terminal.writer().println(
//                            AttributedString.fromAnsi("\u001B[0m\"" + response + "\"")
//                                    .toAnsi(terminal));
//
//                } else {
                terminal.writer().println(response);
//                }
                terminal.flush();
            }}
    }

    private static Crypto CRYPTO;


    public static void main(String[] args) throws Exception {
        CRYPTO = Crypto.initJava();

        Logging.LOG().setLevel(Level.WARNING);

        CLIContext cliContext = buildContextFromCLI();

        new CLI(cliContext).run();
    }
}
