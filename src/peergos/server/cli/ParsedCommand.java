package peergos.server.cli;

import java.util.ArrayList;
import java.util.List;

class ParsedCommand {
    public final Command cmd;
    public final String line;
    public final List<String> arguments;

    ParsedCommand(Command cmd, String line, List<String> arguments) {
        this.cmd = cmd;
        this.line = line;
        this.arguments = new ArrayList<>(arguments); // words without the cmd
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
