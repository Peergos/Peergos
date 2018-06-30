package peergos.server;

import peergos.shared.util.Args;

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class Command {
    public static class Arg {
        public final String name, description;
        public final boolean isRequired;
        public final Optional<String> defaultValue;

        public Arg(String name, String description, boolean isRequired) {
            this.name = name;
            this.description = description;
            this.isRequired = isRequired;
            this.defaultValue = Optional.empty();
        }

        public Arg(String name, String description, boolean isRequired, String defaultValue) {
            this.name = name;
            this.description = description;
            this.isRequired = isRequired;
            this.defaultValue = Optional.of(defaultValue);
        }
    }

    public final String name, description;
    public final Consumer<Args> entryPoint;
    public final List<Arg> params;// with description
    public final Map<String, Command> subCommands;


    public Command(String name, String description,
                   Consumer<Args> entryPoint,
                   List<Arg> params) {
        this(name,description, entryPoint, params, Collections.emptyList());
    }

    public Command(String name, String description,
                   Consumer<Args> entryPoint,
                   List<Arg> params,
                   List<Command> subCommands) {
        this.name = name;
        this.description = description;
        this.entryPoint = entryPoint;
        this.params = params;
        this.subCommands = subCommands.stream().collect(Collectors.toMap(c -> c.name, c -> c));
    }

    public void main(Args args) {
        for (Arg param : params) {
            param.defaultValue.ifPresent(def -> args.setIfAbsent(param.name, def));
        }
        Optional<String> headOpt = args.head();

        if (headOpt.isPresent()) {
            String head = headOpt.get();
            if (head.equals("help")) {
                System.out.println(helpMessage());
                return;
            }
            if (subCommands.containsKey(head)) {
                subCommands.get(head).main(args.tail());
                return;
            }
        }

        ensureArgs(args);
        Runnable runnable = () -> entryPoint.accept(args);
        runnable.run();
    }

    private void ensureArgs(Args args) {
        Optional<String> missing = params.stream()
                .filter(p -> p.isRequired)
                .map(e -> e.name)
                .filter(e -> ! args.hasArg(e))
                .findFirst();
        if (missing.isPresent())
            throw new IllegalStateException(name +" requires argument "+ missing.get());
    }

    public String helpMessage() {
        return name  +": "+ description +
                System.lineSeparator()
                + (params.size() > 0 ? "Parameters: " + System.lineSeparator() : "")
                + params.stream()
                        .map(e -> "\t"+ e.name + ": "+ e.description)
                        .collect(Collectors.joining(System.lineSeparator()))
                + (subCommands.size() > 0 ? "Sub commands:" + System.lineSeparator() : "")
                + subCommands.entrySet().stream()
                .reduce("",
                        (acc , e) -> acc + "\t" + e.getKey() + ": " + e.getValue().description + System.lineSeparator(),
                        (a, b) -> a + b);

    }

}
