package peergos.server.cli;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ParsedCommand {
    public final Command cmd;
    public final String line;
    public final Set<String> flags;
    public final List<String> arguments;

    ParsedCommand(Command cmd, String line, List<String> args) {
        this.cmd = cmd;
        this.line = line;
        this.flags = new HashSet<>();
        this.arguments = new ArrayList<>(); // words without the cmd
        boolean inEscape = false;
        for (int i=0; i < args.size(); i++) {
            String arg = args.get(i);
            if (arg.startsWith("--")) {
                flags.add(arg);
                continue;
            }
            if (arg.startsWith("\"")) {
                inEscape = true;
                arg = arg.substring(1);
                if (arg.endsWith("\""))
                    arg = arg.substring(0, arg.length() - 1);
                arguments.add(arg);
                continue;
            }
            if (arg.endsWith("\"")) {
                inEscape = false;
                arg = arg.substring(0, arg.length() - 1);
                arguments.set(arguments.size() - 1, arguments.get(arguments.size() - 1) + " " + arg);
                continue;
            }
            while (arg.endsWith("\\") && i < args.size() - 1) {
                arg = arg.substring(0, arg.length() - 1) + " " + args.get(i+1);
                i++;
            }

            if (inEscape)
                arguments.set(arguments.size() - 1, arguments.get(arguments.size() - 1) + " " + arg);
            else
                arguments.add(arg);
        }
    }

    public boolean hasArguments() {
        return !arguments.isEmpty();
    }

    public boolean hasSecondArgument() {
        return arguments.size() > 1;
    }

    public boolean hasThirdArgument() {
        return arguments.size() > 2;
    }

    public String firstArgument() {
        if (arguments.size() < 1)
            throw new IllegalStateException("Specifed command " + line + " requires an argument");
        return arguments.get(0);
    }

    public String secondArgument() {
        if (arguments.size() < 2)
            throw new IllegalStateException("Specifed command " + line + " requires a second argument");
        return arguments.get(1);
    }

    public String thirdArgument() {
        if (arguments.size() < 3)
            throw new IllegalStateException("Specifed command " + line + " requires a third argument");
        return arguments.get(2);
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
