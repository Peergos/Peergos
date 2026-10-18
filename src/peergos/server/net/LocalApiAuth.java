package peergos.server.net;

import com.sun.net.httpserver.*;
import peergos.shared.util.*;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** The per launch secret every local only api requires.
 *
 *  Loopback is shared by every process on the machine, and on android by every app, none of
 *  which send the headers a browser does. So only our own ui is given this, by opening it at
 *  a url with a single use launch code that the page swaps for the secret. A code that ends
 *  up on a command line is then worthless once the page has used it.
 */
public class LocalApiAuth {
    public static final String HEADER = "X-Peergos-Local-Token";
    public static final String LAUNCH_PARAM = "local-auth";
    public static final String TOKEN_FILENAME = "local-api.token";

    private static final SecureRandom rnd = new SecureRandom();

    private final byte[] secret = randomHex().getBytes(StandardCharsets.UTF_8);
    private final Set<String> launchCodes = ConcurrentHashMap.newKeySet();

    public String newLaunchCode() {
        String code = randomHex();
        launchCodes.add(code);
        return code;
    }

    public String launchUrl(int port) {
        return "http://localhost:" + port + "/?" + LAUNCH_PARAM + "=" + newLaunchCode();
    }

    public Optional<String> redeem(String code) {
        if (code == null || ! launchCodes.remove(code))
            return Optional.empty();
        return Optional.of(new String(secret, StandardCharsets.UTF_8));
    }

    public boolean isAuthorised(HttpExchange exchange) {
        List<String> supplied = exchange.getRequestHeaders().get(HEADER);
        return supplied != null && supplied.size() == 1 &&
                MessageDigest.isEqual(secret, supplied.get(0).getBytes(StandardCharsets.UTF_8));
    }

    /** For the cli, which can't be handed a launch code: it can call us if it can read this. */
    public void writeTokenFile(Path peergosDir) throws IOException {
        writeOwnerOnly(peergosDir.resolve(TOKEN_FILENAME), new String(secret, StandardCharsets.UTF_8));
    }

    public static Optional<String> readTokenFile(Path peergosDir) {
        try {
            return Optional.of(Files.readString(peergosDir.resolve(TOKEN_FILENAME)).trim());
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** POST to a local only api of the server on this machine, as the cli */
    public static String post(int port, Path peergosDir, String apiPath) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/" + apiPath))
                .POST(HttpRequest.BodyPublishers.noBody());
        readTokenFile(peergosDir).ifPresent(token -> req.header(HEADER, token));
        HttpResponse<String> res = client.send(req.build(), HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200)
            throw new IOException("Local api " + apiPath + " returned " + res.statusCode()
                    + res.headers().firstValue("Trailer").map(t -> ": " + URLDecoder.decode(t, StandardCharsets.UTF_8)).orElse(""));
        return res.body();
    }

    static void writeOwnerOnly(Path file, String contents) throws IOException {
        Files.deleteIfExists(file);
        try {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException e) {
            Files.createFile(file);
        }
        Files.writeString(file, contents);
    }

    private static String randomHex() {
        byte[] raw = new byte[32];
        rnd.nextBytes(raw);
        return ArrayOps.bytesToHex(raw);
    }
}
