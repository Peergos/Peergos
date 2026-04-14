package peergos.server.net;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import peergos.server.UserService;
import peergos.server.util.HttpUtil;
import peergos.server.util.Logging;
import peergos.shared.io.ipfs.api.JSONParser;
import peergos.shared.storage.BlockCache;
import peergos.shared.util.Constants;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.function.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class ConfigHandler implements HttpHandler {
	private static final Logger LOG = Logging.LOG();

    private static final boolean LOGGING = true;
    private static final String LEGACY_SERVER_URL_KEY = "peergos-url";
    private static final String SERVER_URL_KEY = "server-url";
    private final BlockCache cache;
    private final Optional<UserService.LocalAppProperties> localAppProps;

    public ConfigHandler(BlockCache cache, Optional<UserService.LocalAppProperties> localAppProps) {
        this.cache = cache;
        this.localAppProps = localAppProps;
    }

    private static boolean isLoopbackHost(String host) {
        if (host == null || host.isEmpty())
            return false;
        if ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host) || "[::1]".equals(host))
            return true;
            return false;
    }

    private static String validateServerUrl(String serverUrl) {
        String trimmed = serverUrl.trim();
        if (trimmed.isEmpty())
            return "";
        try {
            URL target = new URL(trimmed);
            if (target.getHost() == null || target.getHost().isEmpty())
                throw new IllegalStateException("server-url must include a host");
            if (target.getUserInfo() != null)
                throw new IllegalStateException("server-url must not include embedded credentials");
            if (target.getQuery() != null || target.getRef() != null)
                throw new IllegalStateException("server-url must not include query parameters or fragments");
            boolean secureLoopback = "http".equalsIgnoreCase(target.getProtocol()) && isLoopbackHost(target.getHost());
            if (! "https".equalsIgnoreCase(target.getProtocol()) && ! secureLoopback)
                throw new IllegalStateException("desktop/proxy mode requires https, or http only for a loopback self-hosted server");
            return target.toString();
        } catch (MalformedURLException e) {
            throw new IllegalStateException("Invalid server-url: " + trimmed, e);
        }
    }

    private synchronized Map<String, String> readConfig(Path configFile) throws IOException {
        if (! Files.exists(configFile))
            return new LinkedHashMap<>();
        List<String> lines = Files.readAllLines(configFile, StandardCharsets.UTF_8);
        Map<String, String> config = new LinkedHashMap<>();
        for (String originalLine : lines) {
            String line = originalLine.trim();
            if (line.isEmpty() || line.matches("\\s+"))
                continue;
            int commentPos = line.indexOf("#");
            if (commentPos == 0)
                continue;
            if (commentPos != -1 && line.charAt(commentPos - 1) == ' ')
                line = line.substring(0, commentPos).trim();
            String[] split = line.split("=", 2);
            if (split.length != 2)
                throw new IllegalStateException("Illegal line '" + line + "'");
            config.put(split[0].trim(), split[1].trim());
        }
        return config;
    }

    private synchronized void writeConfig(Path configFile, Map<String, String> config) throws IOException {
        Files.createDirectories(configFile.getParent());
        String text = new TreeMap<>(config).entrySet().stream()
                .map(e -> e.getKey() + " = " + e.getValue())
                .collect(Collectors.joining("\n"));
        Files.write(configFile, text.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private synchronized Optional<String> getConfiguredServerUrl(Path configFile) throws IOException {
        Map<String, String> config = readConfig(configFile);
        if (config.containsKey(SERVER_URL_KEY))
            return Optional.of(config.get(SERVER_URL_KEY));
        return Optional.ofNullable(config.get(LEGACY_SERVER_URL_KEY));
    }

    private synchronized void saveServerUrl(Path configFile, String serverUrl) throws IOException {
        Map<String, String> config = readConfig(configFile);
        if (serverUrl.isEmpty()) {
            config.remove(SERVER_URL_KEY);
            config.remove(LEGACY_SERVER_URL_KEY);
        } else {
            config.put(SERVER_URL_KEY, serverUrl);
            config.put(LEGACY_SERVER_URL_KEY, serverUrl);
        }
        writeConfig(configFile, config);
    }

    private static void replyJson(HttpExchange exchange, Object payload) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        byte[] res = JSONParser.toString(payload).getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, res.length);
        try (OutputStream resp = exchange.getResponseBody()) {
            resp.write(res);
        }
    }

    @Override
    public void handle(HttpExchange exchange) {
        long t1 = System.currentTimeMillis();
        String path = exchange.getRequestURI().getPath();
        try {
            if (! HttpUtil.allowedQuery(exchange, false)) {
                exchange.sendResponseHeaders(405, 0);
                return;
            }
            String host = exchange.getRequestHeaders().get("Host").get(0);
            if (! host.startsWith("localhost:")) {
                exchange.sendResponseHeaders(404, 0);
                exchange.close();
                return;
            }
            if (path.startsWith("/"))
                path = path.substring(1);
            String action = path.substring(Constants.CONFIG.length());
            Map<String, List<String>> params = HttpUtil.parseQuery(exchange.getRequestURI().getQuery());
            Function<String, String> last = key -> params.get(key).get(params.get(key).size() - 1);

            if (action.equals("cache/set-size-mb")) {
                long maxSizeMb = Long.parseLong(last.apply("size"));
                cache.setMaxSize(maxSizeMb * 1024*1024);
                exchange.sendResponseHeaders(200, 0);
                exchange.close();
            } else if (action.equals("cache/get-size")) {
                long cacheSizeBytes = cache.getMaxSize();
                long cacheSizeMB = cacheSizeBytes / (1024 * 1024);
                Map<String, Object> json = new LinkedHashMap<>();
                json.put("size", cacheSizeMB);
                replyJson(exchange, json);
            } else if (action.equals("server-url/get")) {
                if (localAppProps.isEmpty()) {
                    exchange.sendResponseHeaders(404, 0);
                    exchange.close();
                    return;
                }
                UserService.LocalAppProperties props = localAppProps.get();
                Path configFile = props.peergosDir.resolve("config");
                Map<String, Object> json = new LinkedHashMap<>();
                json.put("current", props.currentServerUrl);
                json.put("configured", getConfiguredServerUrl(configFile).orElse(""));
                replyJson(exchange, json);
            } else if (action.equals("server-url/set")) {
                if (localAppProps.isEmpty()) {
                    exchange.sendResponseHeaders(404, 0);
                    exchange.close();
                    return;
                }
                String encoded = params.containsKey("url") ? last.apply("url") : "";
                String newServerUrl = validateServerUrl(URLDecoder.decode(encoded, StandardCharsets.UTF_8));
                UserService.LocalAppProperties props = localAppProps.get();
                saveServerUrl(props.peergosDir.resolve("config"), newServerUrl);
                Map<String, Object> json = new LinkedHashMap<>();
                json.put("serverUrl", newServerUrl);
                json.put("restartRequired", true);
                replyJson(exchange, json);
            } else {
                LOG.info("Unknown config handler: " +exchange.getRequestURI());
                exchange.sendResponseHeaders(404, 0);
                exchange.close();
            }
        } catch (Exception e) {
            LOG.severe("Error handling " +exchange.getRequestURI());
            LOG.log(Level.WARNING, e.getMessage(), e);
            HttpUtil.replyError(exchange, e);
        } finally {
            exchange.close();
            long t2 = System.currentTimeMillis();
            if (LOGGING)
                LOG.info("Config Handler returned in: " + (t2 - t1) + " mS");
        }
    }
}
