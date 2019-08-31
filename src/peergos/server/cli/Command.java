package peergos.server.cli;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.stream.Stream;

public enum Command {
    help("Show this help"),
    exit("Disconnect"),
    get("Download a file", "get remote-path <local path>", Arg.REMOTE, Arg.LOCAL),
    put("Upload a file", "put local-path <remote-path>", Arg.LOCAL, Arg.REMOTE),
    ls("List contents of a remote directory", "ls <path>", Arg.REMOTE),
    rm("Remove a remote-file", "rm remote-path", Arg.REMOTE),
    space("Show used remote space"),
    get_follow_requests("Show the users that have sent you a follow request"),
    process_follow_request("Accept or reject a pending follow-request. Optionally send them a reciprocal follow request.", "accept_follow_request pending-follower accept|accept-and-reciprocate|decline", Arg.PENDING_FOLLOW_REQUEST, Arg.PROCESS_FOLLOW_REQUEST),
    follow("Send a follow-request to another user.", "follow user", Arg.USERNAME),
    share_read("Grant read access for a file to another user.", "share_read path user", Arg.REMOTE, Arg.FOLLOWER),
    passwd("Update your password"),
    cd("change (remote) directory", "cd <path>", Arg.REMOTE),
    pwd("Print (remote) working directory"),
    lpwd("Print (local) working directory"),
    quit("Disconnect"),
    bye("Disconnect");

    public final String description, example;
    public final Arg firstArg, secondArg;

    Command(String description, String example, Arg firstArg, Arg secondArg) {
        if (firstArg == null && secondArg != null)
            throw new IllegalArgumentException();

        this.description = description;
        this.example = example;
        this.firstArg = firstArg;
        this.secondArg = secondArg;
    }

    Command(String description, String example, Arg firstArg) {
        this(description, example, firstArg,null);
    }

    Command(String description, String example) {
        this(description, example, null,null);
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

    public static enum Arg {
        REMOTE, LOCAL, USERNAME, FOLLOWER, PENDING_FOLLOW_REQUEST, PROCESS_FOLLOW_REQUEST;
    }
    public static enum ProcessFollowRequestAction  {
        accept, accept_and_reciprocate, reject;
    }
}
