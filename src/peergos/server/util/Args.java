package peergos.server.util;

import peergos.server.*;
import peergos.shared.util.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Args {
    private static final String CONFIG_FILENAME = "config";

    private final List<String> commands;
    private final Map<String, String> params, envOnly;//insertion order

    public Args(List<String> commands, Map<String, String> params, Map<String, String> envOnly) {
        this.commands = commands;
        this.params = params;
        this.envOnly = envOnly;
    }

    public List<String> commands() {
        return new ArrayList<>(commands);
    }

    public List<String> getAllArgs() {
        Map<String, String> env = System.getenv();
        return params.entrySet().stream()
                .filter(e -> ! env.containsKey(e.getKey()))
                .flatMap(e -> Stream.of("-" + e.getKey(), e.getValue()))
                .collect(Collectors.toList());
    }

    public String getArg(String param, String def) {
        if (!params.containsKey(param))
            return def;
        return params.get(param);
    }

    public String getArg(String param) {
        if (!params.containsKey(param))
            throw new IllegalStateException("No parameter: " + param);
        return params.get(param);
    }

    public Optional<String> getOptionalArg(String param) {
        return Optional.ofNullable(params.get(param));
    }

    public Args setArg(String param, String value) {
        Map<String, String> newParams = paramMap();
        newParams.putAll(params);
        newParams.put(param, value);
        return new Args(commands, newParams, envOnly);
    }

    public Args setParameter(String param) {
        return setArg(param, "true");
    }

    public Args removeArg(String param) {
        Map<String, String> newParams = paramMap();
        newParams.putAll(params);
        newParams.remove(param);
        return new Args(commands, newParams, envOnly);
    }

    public boolean hasArg(String arg) {
        return params.containsKey(arg);
    }

    public boolean getBoolean(String param, boolean def) {
        if (!params.containsKey(param))
            return def;
        return "true".equals(params.get(param));
    }

    public boolean getBoolean(String param) {
        if (!params.containsKey(param))
            throw new IllegalStateException("Missing parameter for " + param);
        return "true".equals(params.get(param));
    }

    public int getInt(String param, int def) {
        if (!params.containsKey(param))
            return def;
        return Integer.parseInt(params.get(param));
    }

    public int getInt(String param) {
        if (!params.containsKey(param))
            throw new IllegalStateException("No parameter: " + param);
        return Integer.parseInt(params.get(param));
    }

    public long getLong(String param) {
        if (!params.containsKey(param))
            throw new IllegalStateException("No parameter: " + param);
        return Long.parseLong(params.get(param));
    }

    public long getLong(String param, long def) {
        if (!params.containsKey(param))
            return def;
        return Long.parseLong(params.get(param));
    }

    public double getDouble(String param) {
        if (!params.containsKey(param))
            throw new IllegalStateException("No parameter: " + param);
        return Double.parseDouble(params.get(param));
    }

    public String getFirstArg(String[] paramNames, String def) {
        for (int i = 0; i < paramNames.length; i++) {
            String result = getArg(paramNames[i], null);
            if (result != null)
                return result;
        }
        return def;
    }

    public Args tail() {
        return new Args(commands.subList(1, commands.size()), params, envOnly);
    }

    public Optional<String> head() {
        return commands.stream()
                .findFirst();
    }

    public Args setIfAbsent(String key, String value) {
        if (params.containsKey(key))
            return this;
        return setArg(key, value);
    }

    public Args with(String key, String value) {
        Map<String, String> map = paramMap();
        map.putAll(params);
        map.put(key, value);
        return new Args(commands, map, envOnly);
    }

    public Args with(Args overrides) {
        Map<String, String> map = paramMap();
        map.putAll(params);
        map.putAll(overrides.params);
        return new Args(commands, map, envOnly);
    }

    public Path fromPeergosDir(String fileName) {
        return fromPeergosDir(fileName, null);
    }

    /**
     * Get the path to a file-name in the PEERGOS_PATH
     *
     * @param fileName
     * @param defaultName
     * @return
     */
    public Path fromPeergosDir(String fileName, String defaultName) {
        Path peergosDir = getPeergosDir();
        String fName = defaultName == null ? getArg(fileName) : getArg(fileName, defaultName);
        return peergosDir.resolve(fName);
    }

    public Path getPeergosDirChild(String filename) {
        return getPeergosDir().resolve(filename);
    }

    public Path getPeergosDir() {
        return hasArg(Main.PEERGOS_PATH) ? Paths.get(getArg(Main.PEERGOS_PATH)) : Main.DEFAULT_PEERGOS_DIR_PATH;
    }

    private static Map<String, String> parseEnv() {
        Map<String, String> map = paramMap();
        map.putAll(System.getenv());
        return map;
    }

    private static Map<String, String> parseFile(Map<String, String> args, Map<String, String> env) {
        Path toFile = (args.containsKey(Main.PEERGOS_PATH) ?
                Paths.get(args.get(Main.PEERGOS_PATH)) :
                Main.DEFAULT_PEERGOS_DIR_PATH).resolve(CONFIG_FILENAME);
        return parseFile(toFile);
    }

    private static Map<String, String> parseFile(Path path) {
        try {
            if (! path.toFile().exists())
                return Collections.emptyMap();
            List<String> lines = Files.readAllLines(path);

            return lines.stream()
                    .filter(line -> !line.isEmpty())
                    .filter(line -> !line.matches("\\s+"))
                    .map(Args::parseLine)
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .collect(Collectors.toMap(
                            e -> e.left,
                            e -> e.right));

        } catch (IOException ioe) {
            throw new IllegalStateException(ioe.getMessage(), ioe);
        }
    }

    /** Save all parameters to a config file if it doesn't exist already
     *
     */
    public void saveToFileIfAbsent() {
        Path config = fromPeergosDir(CONFIG_FILENAME, CONFIG_FILENAME);
        if (! config.toFile().exists())
            saveToFile(config);
    }

    /** Save all parameters to a config file, excluding environment vars
     *
     */
    public void saveToFile() {
        saveToFile(fromPeergosDir(CONFIG_FILENAME, CONFIG_FILENAME));
    }

    private void saveToFile(Path file) {
        String text = new TreeMap<>(params).entrySet().stream()
                .filter(e -> ! envOnly.containsKey(e.getKey()))
                .map(e -> e.getKey() + " = " + e.getValue())
                .collect(Collectors.joining("\n"));
        try {
            Files.write(file, text.getBytes(), StandardOpenOption.CREATE);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static Map<String, String> parseParams(String[] args) {
        Map<String, String> map = paramMap();
        for (int i = 0; i < args.length; i++) {
            String argName = args[i];
            if (argName.startsWith("-"))
                argName = argName.substring(1);

            if ((i == args.length - 1) || args[i + 1].startsWith("-"))
                map.put(argName, "true");
            else
                map.put(argName, args[++i]);
        }
        return map;
    }

    private static List<String> parseCommands(String[] args) {
        List<String> commands = new ArrayList<>();
        for (String arg: args)
            if (! arg.startsWith("-"))
                commands.add(arg);
            else break;
        return commands;
    }

    /**
     * params overrides configFile overrides env
     *
     * @param params
     * @param configFile
     * @param includeEnv
     * @return
     */
    public static Args parse(String[] params, Optional<Path> configFile, boolean includeEnv) {
        List<String> commands = parseCommands(params);
        Map<String, String> fromEnv = includeEnv ? parseEnv() : Collections.emptyMap();
        Map<String, String> fromParams = parseParams(params);
        Map<String, String> fromFile = configFile.isPresent() ?
                parseFile(configFile.get()) :
                parseFile(fromParams, fromEnv);

        Map<String, String> combined = paramMap();

        Stream.of(
                fromParams.entrySet(),
                fromFile.entrySet(),
                fromEnv.entrySet()
        )
                .flatMap(e -> e.stream())
                .forEach(e -> combined.putIfAbsent(e.getKey(), e.getValue()));

        return new Args(commands, combined, fromEnv);
    }

    public static Args parse(String[] args) {
        return parse(args, Optional.empty(), true);
    }

    /**
     * Parses a string with the schema "\\s*\\w+\\s*=\\s*\\w*\\s*#?\\s*"
     * Omits full comments or partial comments
     *
     * @param originalLine
     * @return
     */
    private static Optional<Pair<String, String>> parseLine(String originalLine) {
        String line = originalLine.trim();

        int commentPos = line.indexOf("#");
        // This line is a pure comment
        if (commentPos == 0)
            return Optional.empty();

        // Enforce a space before # for a comment not at start of line
        if (commentPos != -1 && line.charAt(commentPos - 1) == ' ')
            // line ends with a comment
            line = line.substring(0, commentPos).trim();

        String[] split = line.split("=");
        if (split.length != 2)
            throw new IllegalStateException("Illegal line '" + line + "'");

        String left = split[0].trim();
        String right = split[1].trim();

        return Optional.of(new Pair<>(left, right));
    }

    private static <K, V> Map<K, V> paramMap() {
        return new LinkedHashMap<>(16, 0.75f, false);
    }

}
