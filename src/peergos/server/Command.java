package peergos.server;

import peergos.shared.util.Args;

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class Command {

    public static class Arg {
        public final String name, description;
        public final boolean isRequired;

        public Arg(String name, String description, boolean isRequired) {
            this.name = name;
            this.description = description;
            this.isRequired = isRequired;
        }
    }

    public final String name, description;
    public final Consumer<Args> entryPoint;
    public final List<Arg> params;// with description
    public final Map<String, Command> subCommands;


    public Command(String name, String description,
                   Consumer<Args> entryPoint,
                   List<Arg> params) {
        this(name,description, entryPoint, params, Collections.emptyMap());
    }

    public Command(String name, String description,
                   Consumer<Args> entryPoint,
                   List<Arg> params,
                   Map<String, Command> subCommands) {
        this.name = name;
        this.description = description;
        this.entryPoint = entryPoint;
        this.params = params;
        this.subCommands = subCommands;
    }

    public void main(Args args) {
        Optional<String> headOpt = args.head();
        Runnable runnable = () -> entryPoint.accept(args);
        if (headOpt.isPresent()) {
            String head = headOpt.get();
            if (head.equals("--help")) {
                System.out.println(helpMessage());
                return;
            }
            if (subCommands.containsKey(head)) {
                subCommands.get(head).main(args.tail());
                return;
            }
        }

        ensureArgs(args);
        runnable.run();
    }

    private void ensureArgs(Args args) {
        Optional<String> missing = params.stream()
                .map(e -> e.name)
                .filter(e -> !args.hasArg(e))
                .findFirst();
        if (missing.isPresent())
            throw new IllegalStateException(name +" requires argument "+ missing.get());
    }

    public String helpMessage() {
        return name  +": "+ description +
                System.lineSeparator() +
                params.stream()
                        .map(e -> "\t"+ e.name + ": "+ e.description)
                        .collect(Collectors.joining(System.lineSeparator()));

    }

}
