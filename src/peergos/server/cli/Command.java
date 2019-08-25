package peergos.server.cli;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

public enum Command {
    help("Show this help"),
    exit("Disconnect"),
    get("Download a file", "get remote-path <local path>"),
    put("Upload a file", "put local-path <remote-path>"),
    ls("List contents of a remote directory", "ls <path>"),
    rm("Remove a remote-file", "rm remote-path"),
    space("Show used remote space"),
    get_follow_requests("Show the users that have sent you a follow request"),
    follow("Send a follow-request to another user.", "follow username-to-follow"),
    share_read("Grant read access for a file to another user.", "share_read path <user>"),
    passwd("Update your password"),
    cd("change (remote) directory", "cd <path>"),
    pwd("Print (remote) working directory"),
    lpwd("Print (local) working directory"),
    quit("Disconnect"),
    bye("Disconnect");

    public final String description, example;

    Command(String description, String example) {
        this.description = description;
        this.example = example;
    }

    Command(String description) {
        this(description, null);
    }

    public static int maxLength() {
        return Stream.of(values())
                .mapToInt(e -> e.example().length())
                .max()
                .getAsInt();
    }

    public String example() {
        return example == null ? name() : example;
    }

    public static Command parse(String cmd) {
        try {
            return Command.valueOf(cmd);
        } catch (IllegalArgumentException | NullPointerException ex) {
            if ("?".equals(cmd))
                return help;
            throw new IllegalStateException("Specified command " + cmd + " is not a valid command : " + new ArrayList<>(Arrays.asList(values())));
        }
    }

    private static List<Command> COMMANDS_WITH_REMOTE_FILE_FIRST_ARG = new ArrayList<>(Arrays.asList(get, ls, rm, cd));
    private static List<Command> COMMANDS_WITH_REMOTE_FILE_SECOND_ARG = new ArrayList<>(Arrays.asList(put));

    public boolean hasRemoteFileFirstArg()  {
        return COMMANDS_WITH_REMOTE_FILE_FIRST_ARG.contains(this);
    }

    public boolean hasRemoteFileSecondArg() {
        return COMMANDS_WITH_REMOTE_FILE_SECOND_ARG.contains(this);
    }
}
