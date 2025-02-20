package peergos.server.cli;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Stream;

public enum Command {
    help("Show this help."),
    exit("Disconnect."),
    get("Download a file.", "get remote-path <local path>", Set.of(Flag.SKIP_EXISTING), Argument.REMOTE_FILE, Argument.LOCAL_FILE),
    mkdir("Create a directory", "mkdir dir-name", Argument.REMOTE_DIR),
    put("Upload a file or folder.", "put <--skip-existing> local-path <remote-path> ", Set.of(Flag.SKIP_EXISTING), Argument.LOCAL_FILE, Argument.REMOTE_FILE),
    ls("List contents of a remote directory.", "ls <path>", Argument.REMOTE_FILE),
    lls("List contents of a local directory.", "lls <path>", Argument.LOCAL_FILE),
    rm("Remove a remote-file.", "rm remote-path", Argument.REMOTE_FILE),
    space("Show used remote space."),
    get_follow_requests("Show the users that have sent you a follow request."),
    process_follow_request("Accept or reject a pending follow-request.", "process_follow_request pending-follower accept|accept-and-reciprocate|reject", Argument.PENDING_FOLLOW_REQUEST, Argument.PROCESS_FOLLOW_REQUEST),
    follow("Send a follow-request to another user.", "follow user", Argument.USERNAME),
    share_read("Grant read access for a file to another user.", "share_read remote-path user", Argument.REMOTE_FILE, Argument.FOLLOWER),
    passwd("Update your password."),
    cd("change (remote) directory.", "cd <remote-path>", Argument.REMOTE_DIR),
    lcd("change (local) directory.", "lcd local-path", Argument.LOCAL_DIR),
    pwd("Print (remote) working directory."),
    lpwd("Print (local) working directory."),
    quit("Disconnect."),
    bye("Disconnect.");

    public final String description, example;
    public final Set<Flag> flags;
    public final Argument firstArg, secondArg, thirdArg;

    Command(String description, String example, Set<Flag> flags, Argument firstArg, Argument secondArg, Argument thirdArg) {
        if (firstArg == null && secondArg != null)
            throw new IllegalArgumentException();
        if (secondArg == null && thirdArg != null)
            throw new IllegalArgumentException();

        this.description = description;
        this.example = example;
        this.flags = flags;
        this.firstArg = firstArg;
        this.secondArg = secondArg;
        this.thirdArg = thirdArg;
    }

    Command(String description, String example, Set<Flag> flags, Argument firstArg, Argument secondArg) {
        this(description, example, flags, firstArg, secondArg, null);
    }

    Command(String description, String example, Argument firstArg, Argument secondArg) {
        this(description, example, Collections.emptySet(), firstArg, secondArg, null);
    }

    Command(String description, String example, Set<Flag> flags, Argument firstArg) {
        this(description, example, flags, firstArg,null, null);
    }

    Command(String description, String example, Argument firstArg) {
        this(description, example, Collections.emptySet(), firstArg,null, null);
    }

    Command(String description, String example, Set<Flag> flags) {
        this(description, example, flags, null,null, null);
    }

    Command(String description, Set<Flag> flags) {
        this(description, null, flags);
    }

    Command(String description) {
        this(description, Collections.emptySet());
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

    public enum Argument {
        REMOTE_FILE,
        REMOTE_DIR,
        LOCAL_FILE,
        LOCAL_DIR,
        SKIP_EXISTING,
        USERNAME,
        FOLLOWER,
        PENDING_FOLLOW_REQUEST,
        PROCESS_FOLLOW_REQUEST;
    }

    public enum Flag {
        SKIP_EXISTING("--skip-existing");

        public final String flag;

        Flag(String flag) {
            this.flag = flag;
        }
    }

    public enum ProcessFollowRequestAction  {
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
