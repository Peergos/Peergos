package peergos.server.net;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import peergos.server.util.HttpUtil;
import peergos.server.util.Logging;
import peergos.server.webauthn.Ctap2;
import peergos.server.webauthn.SecurityKey;
import peergos.shared.io.ipfs.api.JSONParser;
import peergos.shared.util.Constants;
import peergos.shared.util.Serialize;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Drives a security key on behalf of the local web ui.
 *
 *  The webview in the desktop app either has no WebAuthn of its own, or has one that
 *  can only mint credentials for rpId localhost, so the app is the user agent here,
 *  exactly as the android app is: it builds the clientDataJSON and asks the key to
 *  sign it. That is also what lets a key registered in a browser for peergos.net be
 *  used from localhost, which no browser will do, because the origin we claim follows
 *  the relying party rather than the window.
 *
 *  How we then reach the key is the one platform specific part, and lives behind
 *  SecurityKey.
 */
public class WebAuthnHandler implements HttpHandler {
    private static final Logger LOG = Logging.LOG();
    private static final long TOUCH_TIMEOUT_MILLIS = 60_000;
    private static final List<Long> DEFAULT_ALGORITHMS = List.of(-7L, -257L); // ES256, RS256

    private final String defaultRpId;
    private final int localPort;
    private final SecurityKey key;
    private final Object oneAtATime = new Object();

    public WebAuthnHandler(String appServerUrl, int localPort) {
        this.defaultRpId = hostOf(appServerUrl);
        this.localPort = localPort;
        this.key = SecurityKey.forThisPlatform();
    }

    private static String hostOf(String serverUrl) {
        try {
            String host = new URL(serverUrl).getHost();
            return host == null || host.isEmpty() ? "localhost" : host;
        } catch (Exception e) {
            return "localhost";
        }
    }

    /** The origin a browser would have had, had it been able to do this at all. */
    private String originFor(String rpId) {
        return rpId.equals("localhost") ? uiOrigin() : "https://" + rpId;
    }

    private String uiOrigin() {
        return "http://localhost:" + localPort;
    }

    /** Only ever our own relying party.
     *
     *  This is the control that matters: without it, anything that can reach this
     *  port could ask the key to sign for google.com and the user would have no way
     *  to tell what the touch was for. Pinned here, the worst a caller can get is an
     *  assertion for this user's Peergos credential, which is useless without the
     *  challenge the account server issued.
     */
    private String checkedRpId(Object requested) {
        if (requested == null)
            return defaultRpId;
        String rpId = requested.toString();
        if (! rpId.equals(defaultRpId))
            throw new IllegalArgumentException("This app only signs in to " + defaultRpId + ", not " + rpId);
        return rpId;
    }

    @Override
    public void handle(HttpExchange exchange) {
        try {
            if (! HttpUtil.allowedQuery(exchange, false)) {
                exchange.sendResponseHeaders(405, 0);
                return;
            }
            String host = exchange.getRequestHeaders().get("Host").get(0);
            if (! host.startsWith("localhost:")) {
                exchange.sendResponseHeaders(404, 0);
                return;
            }
            // A page in the user's browser can POST here cross site without a preflight,
            // so anything that admits to another origin is refused. We never send any
            // Access-Control-Allow-Origin, so such a caller cannot read a reply either.
            List<String> origins = exchange.getRequestHeaders().get("Origin");
            if (origins != null && ! origins.isEmpty() && ! uiOrigin().equals(origins.get(0))) {
                LOG.info("Refusing webauthn request from origin " + origins.get(0));
                exchange.sendResponseHeaders(403, 0);
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if (path.startsWith("/"))
                path = path.substring(1);
            String action = path.substring(Constants.WEBAUTHN.length());
            Map<String, Object> body = (Map<String, Object>) JSONParser.parse(
                    new String(Serialize.readFully(exchange.getRequestBody()), StandardCharsets.UTF_8));

            if (action.equals("host")) {
                reply(exchange, setHostWindow(body));
                return;
            }

            Map<String, Object> options = (Map<String, Object>) body.get("publicKey");
            if (options == null)
                throw new IllegalArgumentException("No publicKey options");

            Map<String, Object> result;
            // one ceremony at a time: there is a single key, waiting to be touched
            synchronized (oneAtATime) {
                if (action.equals("create"))
                    result = register(options);
                else if (action.equals("get"))
                    result = authenticate(options);
                else {
                    LOG.info("Unknown webauthn action: " + action);
                    exchange.sendResponseHeaders(404, 0);
                    return;
                }
            }
            reply(exchange, result);
        } catch (Exception e) {
            LOG.log(Level.WARNING, e.getMessage(), e);
            HttpUtil.replyError(exchange, e);
        } finally {
            exchange.close();
        }
    }

    private Map<String, Object> register(Map<String, Object> options) throws IOException {
        Map<String, Object> rp = (Map<String, Object>) options.get("rp");
        String rpId = checkedRpId(rp == null ? null : rp.get("id"));
        String rpName = rp != null && rp.get("name") != null ? (String) rp.get("name") : "Peergos";
        Map<String, Object> user = (Map<String, Object>) options.get("user");
        if (user == null)
            throw new IllegalArgumentException("No user in the registration options");
        byte[] clientData = clientDataJson("webauthn.create", bytes(options.get("challenge")), rpId);

        Ctap2.Credential credential = key.makeCredential(clientData, rpId, rpName,
                bytes(user.get("id")), string(user.get("name")), string(user.get("displayName")),
                algorithms(options), System.currentTimeMillis() + TOUCH_TIMEOUT_MILLIS);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("attestationObject", base64url(credential.attestationObject));
        response.put("clientDataJSON", base64url(clientData));
        return credentialJson(credential.credentialId, response);
    }

    private Map<String, Object> authenticate(Map<String, Object> options) throws IOException {
        String rpId = checkedRpId(options.get("rpId"));
        byte[] clientData = clientDataJson("webauthn.get", bytes(options.get("challenge")), rpId);
        List<byte[]> allowed = new ArrayList<>();
        Object allowCredentials = options.get("allowCredentials");
        if (allowCredentials instanceof List) {
            for (Object entry : (List<Object>) allowCredentials)
                allowed.add(bytes(((Map<String, Object>) entry).get("id")));
        }

        Ctap2.Credential credential = key.getAssertion(clientData, rpId, allowed,
                System.currentTimeMillis() + TOUCH_TIMEOUT_MILLIS);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("clientDataJSON", base64url(clientData));
        response.put("authenticatorData", base64url(credential.authenticatorData));
        response.put("signature", base64url(credential.signature));
        if (credential.userHandle != null)
            response.put("userHandle", base64url(credential.userHandle));
        return credentialJson(credential.credentialId, response);
    }

    /** The desktop host telling us about its window, so a platform prompt - the windows
     *  one - can be modal to it and come to the front. The java server has no window of
     *  its own, and the host is a different process.
     *
     *  Nothing is trusted to this beyond where a dialog gets parented: the worst a local
     *  caller can do with a made up handle is send the prompt somewhere unhelpful, which
     *  is exactly what happens today with no handle at all.
     */
    private Map<String, Object> setHostWindow(Map<String, Object> body) {
        Object handle = body.get("window");
        if (! (handle instanceof Number) && ! (handle instanceof String))
            throw new IllegalArgumentException("No window handle");
        key.setWindowHandle(handle instanceof Number ? ((Number) handle).longValue()
                : Long.parseLong(handle.toString()));
        return Map.of("ok", true);
    }

    private static void reply(HttpExchange exchange, Map<String, Object> result) throws IOException {
        byte[] reply = JSONParser.toString(result).getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, reply.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(reply);
        }
    }

    private static Map<String, Object> credentialJson(byte[] credentialId, Map<String, Object> response) {
        String id = base64url(credentialId);
        Map<String, Object> credential = new LinkedHashMap<>();
        credential.put("id", id);
        credential.put("rawId", id);
        credential.put("type", "public-key");
        credential.put("response", response);
        return credential;
    }

    private byte[] clientDataJson(String type, byte[] challenge, String rpId) {
        String json = "{\"type\":\"" + type + "\",\"challenge\":\"" + base64url(challenge)
                + "\",\"origin\":\"" + originFor(rpId) + "\",\"crossOrigin\":false}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static List<Long> algorithms(Map<String, Object> options) {
        Object params = options.get("pubKeyCredParams");
        if (! (params instanceof List) || ((List) params).isEmpty())
            return DEFAULT_ALGORITHMS;
        List<Long> algorithms = new ArrayList<>();
        for (Object entry : (List<Object>) params) {
            Object algorithm = ((Map<String, Object>) entry).get("alg");
            if (algorithm instanceof Number)
                algorithms.add(((Number) algorithm).longValue());
        }
        return algorithms.isEmpty() ? DEFAULT_ALGORITHMS : algorithms;
    }

    /** The page hands us byte arrays as {_type:'ArrayBuffer', data:[...]}. */
    private static byte[] bytes(Object value) {
        if (value instanceof Map)
            value = ((Map<String, Object>) value).get("data");
        if (value instanceof String)
            return Base64.getUrlDecoder().decode((String) value);
        if (! (value instanceof List))
            throw new IllegalArgumentException("Expected bytes, got " + value);
        List<Object> numbers = (List<Object>) value;
        byte[] result = new byte[numbers.size()];
        for (int i = 0; i < result.length; i++)
            result[i] = (byte) ((Number) numbers.get(i)).intValue();
        return result;
    }

    private static String string(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String base64url(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }
}
