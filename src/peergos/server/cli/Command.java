package peergos.server.cli;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.stream.Stream;

public enum Command {
    help("Show this help."),
    exit("Disconnect."),
    get("Download a file.", "get remote-path <local path>", Argument.REMOTE_FILE, Argument.LOCAL_FILE),
    put("Upload a file.", "put local-path <remote-path>", Argument.LOCAL_FILE, Argument.REMOTE_FILE),
    ls("List contents of a remote directory.", "ls <path>", Argument.REMOTE_FILE),
    rm("Remove a remote-file.", "rm remote-path", Argument.REMOTE_FILE),
    space("Show used remote space."),
    get_follow_requests("Show the users that have sent you a follow request."),
    process_follow_request("Accept or reject a pending follow-request.", "process_follow_request pending-follower accept|accept-and-reciprocate|reject", Argument.PENDING_FOLLOW_REQUEST, Argument.PROCESS_FOLLOW_REQUEST),
    follow("Send a follow-request to another user.", "follow user", Argument.USERNAME),
    share_read("Grant read access for a file to another user.", "share_read remote-path user", Argument.REMOTE_FILE, Argument.FOLLOWER),
    passwd("Update your password."),
    cd("change (remote) directory.", "cd remote-path", Argument.REMOTE_DIR),
    pwd("Print (remote) working directory."),
    lpwd("Print (local) working directory."),
    quit("Disconnect."),
    bye("Disconnect.");

    public final String description, example;
    public final Argument firstArg, secondArg;

    Command(String description, String example, Argument firstArg, Argument secondArg) {
        if (firstArg == null && secondArg != null)
            throw new IllegalArgumentException();

        this.description = description;
        this.example = example;
        this.firstArg = firstArg;
        this.secondArg = secondArg;
    }

    Command(String description, String example, Argument firstArg) {
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

    public static enum Argument {
        REMOTE_FILE,
        REMOTE_DIR,
        LOCAL_FILE,
        USERNAME,
        FOLLOWER,
        PENDING_FOLLOW_REQUEST,
        PROCESS_FOLLOW_REQUEST;
    }

    public static enum ProcessFollowRequestAction  {
        accept,
        accept_and_reciprocate("accept-and-reciprocate"),
        reject;

        private String alternative;

        ProcessFollowRequestAction() {
            this(null);
        }
        ProcessFollowRequestAction(String alternative) {
            this.alternative = alternative;
        }

        public static ProcessFollowRequestAction parse(String s) {
            try {
                return Command.ProcessFollowRequestAction.valueOf(s);
            } catch (IllegalArgumentException | NullPointerException ex) {
                // try alternative
                for (ProcessFollowRequestAction value : ProcessFollowRequestAction.values()) {
                    if (s.equals(value.alternative))
                        return value;
                }
                throw ex;
            }
        }

        public String altOrName() {
            return alternative == null ? name() : alternative;
        }
    }


}
