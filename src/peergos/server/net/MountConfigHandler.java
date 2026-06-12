package peergos.server.net;

import com.sun.net.httpserver.*;
import peergos.server.Builder;
import peergos.server.Main;
import peergos.server.MountProperties;
import peergos.server.cfapi.WindowsVersionCheck;
import peergos.server.mount.CloudFilesBackend;
import peergos.server.mount.MountBackend;
import peergos.server.mount.WebdavBackend;
import peergos.server.webdav.MountConfig;
import peergos.server.util.HttpUtil;
import peergos.server.util.Logging;
import peergos.server.util.secrets.SecretStore;
import peergos.shared.Crypto;
import peergos.shared.NetworkAccess;
import peergos.shared.io.ipfs.api.JSONParser;
import peergos.shared.login.mfa.MultiFactorAuthMethod;
import peergos.shared.login.mfa.MultiFactorAuthRequest;
import peergos.shared.login.mfa.MultiFactorAuthResponse;
import peergos.shared.login.mfa.TotpKey;
import peergos.shared.user.UserContext;
import peergos.shared.util.Constants;
import peergos.shared.util.Either;
import peergos.shared.util.Futures;
import peergos.shared.util.Serialize;
import com.eatthepath.otp.TimeBasedOneTimePasswordGenerator;

import javax.crypto.spec.SecretKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.*;

public class MountConfigHandler implements HttpHandler {
    private static final Logger LOG = Logging.LOG();
    /** How often to re-run signIn against peergos to confirm password + 2FA still work.
     *  If the user changes their password (or removes the mount's TOTP) on another device,
     *  the existing session may keep working until its token expires; this check is what
     *  surfaces the change so we can disable the mount and prompt the user to re-mount. */
    private static final long CREDENTIAL_CHECK_INTERVAL_MIN = 60;

    /** Service name used for all mount-related secrets in the OS keyring. */
    private static final String SECRET_SERVICE = "peergos-mount";

    private final Path peergosDir;
    private final String peergosUrl;
    private final SecretStore secretStore;
    private final MountBackend backend;
    private final java.util.function.Supplier<Crypto> cryptoFactory;
    private final java.util.function.Supplier<NetworkAccess> networkFactory;
    private final AtomicReference<String> mountError = new AtomicReference<>(null);
    private final AtomicReference<String> activePeergosUsername = new AtomicReference<>("");
    private final ScheduledExecutorService loginScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "peergos-mount-relogin");
        t.setDaemon(true);
        return t;
    });
    private final AtomicReference<ScheduledFuture<?>> credentialCheck = new AtomicReference<>(null);

    public MountConfigHandler(MountProperties props) {
        this(props, SecretStore.detect(), defaultBackend(props.peergosUrl),
                Main::initCrypto, defaultNetworkFactory(props.peergosUrl));
    }

    public MountConfigHandler(MountProperties props, SecretStore secretStore, MountBackend backend) {
        this(props, secretStore, backend, Main::initCrypto, defaultNetworkFactory(props.peergosUrl));
    }

    public MountConfigHandler(MountProperties props, SecretStore secretStore, MountBackend backend,
                              java.util.function.Supplier<Crypto> cryptoFactory,
                              java.util.function.Supplier<NetworkAccess> networkFactory) {
        this.peergosDir = props.peergosDir;
        this.peergosUrl = props.peergosUrl;
        this.secretStore = secretStore;
        this.backend = backend;
        this.cryptoFactory = cryptoFactory;
        this.networkFactory = networkFactory;
    }

    private static MountBackend defaultBackend(String peergosUrl) {
        if (WindowsVersionCheck.isCfApiAvailable())
            return new CloudFilesBackend();
        return new WebdavBackend(peergosUrl);
    }

    private static java.util.function.Supplier<NetworkAccess> defaultNetworkFactory(String peergosUrl) {
        return () -> {
            try {
                return Builder.buildJavaNetworkAccess(
                        new URL(peergosUrl), peergosUrl.startsWith("https"),
                        Optional.of("Peergos-webdav"), Optional.empty()).join();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
    }

    public void start() {
        MountConfig config = readConfig();
        if (config.enabled) {
            mountError.set(null);
            ForkJoinPool.commonPool().execute(() -> {
                try { enableMount(config); }
                catch (Exception e) {
                    LOG.log(Level.WARNING, "Failed to restore WebDAV mount on startup", e);
                    mountError.set(e.getMessage());
                }
            });
        }
    }

    private synchronized MountConfig readConfig() {
        return readConfig(peergosDir, secretStore);
    }

    public static synchronized MountConfig readConfig(Path peergosDir) {
        return readConfig(peergosDir, SecretStore.detect());
    }

    /** Load the persisted mount config, splicing in keyring-backed secrets when applicable. */
    public static synchronized MountConfig readConfig(Path peergosDir, SecretStore secretStore) {
        Path configFile = peergosDir.resolve(MountConfig.FILENAME);
        if (!configFile.toFile().exists())
            return MountConfig.disabled();
        try {
            String json = Files.readString(configFile);
            MountConfig config = MountConfig.fromJson((Map<String, Object>) JSONParser.parse(json));
            if (secretStore.embedsInConfigFile() || config.peergosUsername.isEmpty())
                return config;
            // Keyring-backed: fetch the secrets that were redacted from the JSON.
            String password = secretStore.get(SECRET_SERVICE, config.peergosUsername + ":password").orElse("");
            String totp     = secretStore.get(SECRET_SERVICE, config.peergosUsername + ":totp").orElse("");
            return config.withSecrets(password, totp);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private synchronized void saveConfig(MountConfig config) {
        try {
            if (!secretStore.embedsInConfigFile()) {
                // Push secrets into the OS keyring first, then redact them from the JSON.
                if (!config.peergosPassword.isEmpty())
                    secretStore.put(SECRET_SERVICE, config.peergosUsername + ":password", config.peergosPassword);
                if (config.hasTotp())
                    secretStore.put(SECRET_SERVICE, config.peergosUsername + ":totp", config.totpSecret);
                else
                    secretStore.delete(SECRET_SERVICE, config.peergosUsername + ":totp");
            }
            MountConfig toWrite = secretStore.embedsInConfigFile() ? config : config.withoutSecrets();
            Files.write(peergosDir.resolve(MountConfig.FILENAME),
                    JSONParser.toString(toWrite.toJson()).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void enableMount(MountConfig config) throws Exception {
        UserContext context = buildContext(config);
        backend.enable(config, context, peergosDir);
        activePeergosUsername.set(config.peergosUsername);
        scheduleCredentialCheck(config);
    }

    private void scheduleCredentialCheck(MountConfig config) {
        // Capture only the username; re-read the config (and its secrets) on each tick
        // so a keyring rotation surfaces on the next check rather than at JVM restart.
        String username = config.peergosUsername;
        ScheduledFuture<?> next = loginScheduler.scheduleAtFixedRate(
                () -> verifyCredentials(username),
                CREDENTIAL_CHECK_INTERVAL_MIN, CREDENTIAL_CHECK_INTERVAL_MIN, TimeUnit.MINUTES);
        ScheduledFuture<?> prev = credentialCheck.getAndSet(next);
        if (prev != null) prev.cancel(false);
    }

    /** Re-run signIn against peergos to make sure the stored password (and TOTP, if any)
     *  are still accepted. On a definitive credential failure we tear the mount down so
     *  the UI surfaces an error and the user can re-mount with fresh credentials; on
     *  transient failures (network, server down) we just log and wait for the next tick. */
    private void verifyCredentials(String username) {
        MountConfig config = readConfig();
        if (!config.enabled || !config.peergosUsername.equals(username))
            return; // Mount was disabled or replaced while we were waiting to fire.
        try {
            Crypto crypto = cryptoFactory.get();
            NetworkAccess network = networkFactory.get();
            // Non-interactive MFA: TOTP if we have one, otherwise an immediate failure
            // (we have no console to read from in a background scheduler thread).
            Function<MultiFactorAuthRequest, CompletableFuture<MultiFactorAuthResponse>> mfa =
                    config.hasTotp()
                            ? mountTotpResponder(config)
                            : req -> Futures.errored(new IllegalStateException(
                                    "Mount credential check requires MFA but no TOTP is stored"));
            UserContext.signIn(config.peergosUsername, config.peergosPassword,
                    mfa, network, crypto).join();
            LOG.fine("Mount credential check OK for " + config.peergosUsername);
        } catch (Throwable t) {
            if (isCredentialFailure(t)) {
                String msg = rootMessage(t);
                LOG.log(Level.WARNING, "Mount credentials no longer valid — disabling mount: " + msg);
                mountError.set("Credentials no longer valid: " + msg);
                disableMount();
            } else {
                LOG.log(Level.INFO, "Mount credential check failed transiently (will retry next hour): "
                        + rootMessage(t));
            }
        }
    }

    /** True for definitive "your password / TOTP isn't accepted" failures from signIn —
     *  used to distinguish from transient network/server errors so we only tear down the
     *  mount when the credentials themselves are the problem. */
    private static boolean isCredentialFailure(Throwable t) {
        while (t != null) {
            String msg = t.getMessage();
            if (msg != null && (msg.contains("Incorrect username or password")
                    || msg.contains("Incorrect password")
                    || msg.contains("Mount TOTP credential not found"))) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null) cur = cur.getCause();
        return cur.getMessage() == null ? cur.getClass().getSimpleName() : cur.getMessage();
    }

    private UserContext buildContext(MountConfig config) throws Exception {
        Crypto crypto = cryptoFactory.get();
        NetworkAccess network = networkFactory.get();
        // If the mount was provisioned with a dedicated TOTP, use that for MFA; falls back
        // to the interactive CLI prompt for legacy mounts that don't have one.
        Function<MultiFactorAuthRequest, CompletableFuture<MultiFactorAuthResponse>> mfa =
                config.hasTotp() ? mountTotpResponder(config) : Main::getMfaResponseCLI;
        return UserContext.signIn(config.peergosUsername, config.peergosPassword,
                mfa, network, crypto).join();
    }

    public static Function<MultiFactorAuthRequest, CompletableFuture<MultiFactorAuthResponse>>
    mountTotpResponder(MountConfig config) {
        return mountTotpResponder(config.totpCredentialIdBytes(), config.totpSecretBytes());
    }

    /**
     * Build a non-interactive MFA responder that generates a TOTP code from the
     * mount's stored secret. Matches the request's expected TOTP method against the
     * stored credentialId — if the user has multiple TOTP factors, we pick OURS so
     * the rest of the user's authenticator apps don't have to be involved.
     *
     * Takes the raw bytes (not a {@link MountConfig}) so the returned closure
     * doesn't pin a reference to a config object that may be re-read later from
     * the keyring with different secrets.
     */
    public static Function<MultiFactorAuthRequest, CompletableFuture<MultiFactorAuthResponse>>
    mountTotpResponder(byte[] credentialId, byte[] secret) {
        TimeBasedOneTimePasswordGenerator totp;
        try {
            totp = new TimeBasedOneTimePasswordGenerator(Duration.ofSeconds(30L), 6, TotpKey.ALGORITHM);
        } catch (Exception e) { throw new RuntimeException(e); }
        return req -> {
            // Prefer a TOTP entry whose credentialId matches our stored one.
            MultiFactorAuthMethod ours = req.methods.stream()
                    .filter(m -> m.type == MultiFactorAuthMethod.Type.TOTP)
                    .filter(m -> Arrays.equals(m.credentialId, credentialId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "Mount TOTP credential not found in user's 2FA methods — was it deleted?"));
            try {
                String code = totp.generateOneTimePasswordString(
                        new SecretKeySpec(secret, TotpKey.ALGORITHM), Instant.now());
                return Futures.of(new MultiFactorAuthResponse(ours.credentialId, Either.a(code)));
            } catch (Exception e) {
                throw new RuntimeException("Failed to generate mount TOTP code", e);
            }
        };
    }

    private void disableMount() {
        ScheduledFuture<?> check = credentialCheck.getAndSet(null);
        if (check != null) check.cancel(false);
        backend.disable();
        activePeergosUsername.set("");
    }

    private static String generateToken() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder(32);
        for (byte b : bytes)
            sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static int findFreePort() throws IOException {
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            s.setReuseAddress(true);
            return s.getLocalPort();
        }
    }

    @Override
    public void handle(HttpExchange exchange) {
        long t1 = System.currentTimeMillis();
        String path = exchange.getRequestURI().getPath();
        try {
            if (!HttpUtil.allowedQuery(exchange, false)) {
                exchange.sendResponseHeaders(405, 0);
                return;
            }
            String host = exchange.getRequestHeaders().get("Host").get(0);
            if (!host.startsWith("localhost:")) {
                exchange.sendResponseHeaders(404, 0);
                exchange.close();
                return;
            }
            if (path.startsWith("/"))
                path = path.substring(1);
            String action = path.substring(Constants.MOUNT.length());

            if (action.equals("get-config")) {
                MountConfig config = readConfig();
                Optional<String> activeMountPoint = backend.activeMountPoint();
                boolean mountActive = activeMountPoint.isPresent();
                String mountPoint = activeMountPoint.orElse("");
                Map<String, Object> json = new LinkedHashMap<>();
                json.put("enabled", mountActive || config.enabled);
                json.put("peergosUsername", mountActive ? activePeergosUsername.get() : config.peergosUsername);
                json.put("webdavUsername", config.webdavUsername);
                json.put("webdavPort", config.webdavPort);
                json.put("authType", config.authType);
                json.put("mountPoint", mountPoint);
                String err = mountError.get();
                if (err != null) json.put("error", err);
                byte[] res = JSONParser.toString(json).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, res.length);
                exchange.getResponseBody().write(res);

            } else if (action.equals("enable")) {
                Map<String, Object> body = (Map<String, Object>) JSONParser.parse(
                        new String(Serialize.readFully(exchange.getRequestBody())));
                String peergosUsername = (String) body.get("peergosUsername");
                String peergosPassword = (String) body.get("peergosPassword");
                boolean autoMount = body.get("autoMount") instanceof Boolean ? (Boolean) body.get("autoMount") : true;
                String authType = "digest";
                String webdavUsername = generateToken();
                String webdavPassword = generateToken();
                int webdavPort = findFreePort();
                // Optional TOTP credential supplied by the UI when the user had 2FA enabled.
                // Both hex-encoded; empty/missing means the mount logs in with password only.
                String totpCredentialId = (String) body.getOrDefault("totpCredentialId", "");
                String totpSecret       = (String) body.getOrDefault("totpSecret", "");

                disableMount();
                MountConfig config = new MountConfig(true, peergosUsername, peergosPassword,
                        webdavUsername, webdavPassword, webdavPort, authType,
                        totpCredentialId, totpSecret);
                if (autoMount) saveConfig(config);
                // Native mount can block (e.g. gio mount on Linux awaits D-Bus); run in background
                // and let the UI poll get-config for the mount point.
                mountError.set(null);
                ForkJoinPool.commonPool().execute(() -> {
                    try { enableMount(config); }
                    catch (Exception e) {
                        LOG.log(Level.WARNING, "Failed to enable WebDAV mount", e);
                        mountError.set(e.getMessage());
                    }
                });
                exchange.sendResponseHeaders(200, 0);

            } else if (action.equals("disable")) {
                // Capture the username before tearing down so we know whose keyring
                // entries to clear; verifyCredentials' transient disables don't take
                // this path, so they leave keyring entries intact for the next re-mount.
                String username = readConfig().peergosUsername;
                disableMount();
                peergosDir.resolve(MountConfig.FILENAME).toFile().delete();
                if (!username.isEmpty() && !secretStore.embedsInConfigFile()) {
                    try { secretStore.delete(SECRET_SERVICE, username + ":password"); }
                    catch (IOException e) { LOG.log(Level.WARNING, "Failed to clear mount password from keyring", e); }
                    try { secretStore.delete(SECRET_SERVICE, username + ":totp"); }
                    catch (IOException e) { LOG.log(Level.WARNING, "Failed to clear mount TOTP from keyring", e); }
                }
                exchange.sendResponseHeaders(200, 0);
            } else {
                LOG.info("Unknown mount config action: " + action);
                exchange.sendResponseHeaders(404, 0);
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error handling " + exchange.getRequestURI(), e);
            HttpUtil.replyError(exchange, e);
        } finally {
            exchange.close();
            LOG.info("Mount Config Handler returned in: " + (System.currentTimeMillis() - t1) + " mS");
        }
    }
}
