package peergos.server.util;

import peergos.server.*;
import peergos.shared.util.Pair;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Args {

    private final Map<String, String> params = paramMap();//insertion order

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

    public String setArg(String param, String value) {
        return params.put(param, value);
    }

    public String setParameter(String param) {
        return params.put(param, "true");
    }

    public String removeParameter(String param) {
        return params.remove(param);
    }

    public String removeArg(String param) {
        return params.remove(param);
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
        Args tail = new Args();
        boolean isFirst = true;

        for (Iterator<Map.Entry<String, String>> it = params.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, String> next = it.next();
            if (!isFirst)
                tail.params.put(next.getKey(), next.getValue());
            isFirst = false;
        }
        return tail;
    }

    public Optional<String> head() {
        return params.keySet()
                .stream()
                .findFirst();
    }

    public void setIfAbsent(String key, String value) {
        params.putIfAbsent(key, value);
    }

    public Args with(String key, String value) {
        Map<String, String> map = paramMap();
        map.putAll(params);
        map.put(key, value);
        Args args = new Args();
        args.params.putAll(map);
        return args;
    }

    public Args with(Args overrides) {
        Map<String, String> map = paramMap();
        map.putAll(params);
        map.putAll(overrides.params);
        Args args = new Args();
        args.params.putAll(map);
        return args;
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

    private static Map<String, String> parseFile(Path path) {
        try {
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

    /**
     * params overrides configFile overrides env
     *
     * @param params
     * @param configFile
     * @param includeEnv
     * @return
     */
    public static Args parse(String[] params, Optional<Path> configFile, boolean includeEnv) {
        Map<String, String> fromParams = parseParams(params);
        Map<String, String> fromConfig = configFile.isPresent() ? parseFile(configFile.get()) : Collections.emptyMap();
        Map<String, String> fromEnv = includeEnv ? parseEnv() : Collections.emptyMap();

        Args args = new Args();

        Stream.of(
                fromParams.entrySet(),
                fromConfig.entrySet(),
                fromEnv.entrySet()
        )
                .flatMap(e -> e.stream())
                .forEach(e -> args.params.putIfAbsent(e.getKey(), e.getValue()));


        return args;
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
