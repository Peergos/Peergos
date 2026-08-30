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
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
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

    /** Raised when this mount's second factor is no longer on the account, which is how revoking it
     *  from 2FA settings on another device reaches us. */
    private static final String REVOKED_ERROR = "This mount's second factor has been revoked";

    private final Path peergosDir;
    private final String peergosUrl;
    private final SecretStore secretStore;
    private final MountBackend backend;
    private final java.util.function.Supplier<Crypto> cryptoFactory;
    private final java.util.function.Supplier<NetworkAccess> networkFactory;
    private final AtomicReference<String> mountError = new AtomicReference<>(null);
    private final AtomicReference<String> activePeergosUsername = new AtomicReference<>("");
    /** The context the running mount logged in with, kept so tearing down doesn't have to sign in
     *  all over again - that means scrypt and a network round trip, which is slow on a phone. */
    private final AtomicReference<UserContext> activeContext = new AtomicReference<>(null);
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
            // readAllBytes, not readString: android's java.nio.file.Files has no
            // readString, and this runs there when the app bootstraps a saved mount
            String json = new String(Files.readAllBytes(configFile), StandardCharsets.UTF_8);
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

    /** The config always holds the webdav password, and the peergos password too where no
     *  keyring is available, so it is created unreadable to anyone else from the start. */
    private static FileAttribute<?>[] ownerOnly(Path dir) {
        return dir.getFileSystem().supportedFileAttributeViews().contains("posix") ?
                new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rw-------"))} :
                new FileAttribute<?>[0];
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
            // written beside the config and swapped in: a half written file reads as a mount
            // that cannot log in, and readConfig holds a different lock from this method
            Path partial = Files.createTempFile(peergosDir, MountConfig.FILENAME, ".tmp", ownerOnly(peergosDir));
            try {
                Files.write(partial, JSONParser.toString(toWrite.toJson()).getBytes(StandardCharsets.UTF_8));
                Files.move(partial, peergosDir.resolve(MountConfig.FILENAME),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                // a successful move leaves nothing behind; a failed one must not leave the
                // secrets sitting in a file that disabling the mount will never clean up
                try { Files.deleteIfExists(partial); } catch (IOException ignored) {}
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void enableMount(MountConfig config) throws Exception {
        UserContext context = buildContext(config);
        backend.enable(config, context, peergosDir);
        activeContext.set(context);
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
            UserContext context = UserContext.signIn(config.peergosUsername, config.peergosPassword,
                    mfa, network, crypto).join();
            // Signing in isn't proof on its own that our factor survived: revoking it can leave the
            // account with no second factor at all, and then login isn't challenged for anything and
            // the password alone would let this check pass forever. Ask whether it is still there.
            if (config.hasTotp() && ! hasOurCredential(context, config))
                throw new IllegalStateException(REVOKED_ERROR);
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

    /** Whether the second factor this mount holds is still on the account. */
    public static boolean hasOurCredential(UserContext context, MountConfig config) {
        byte[] ours = config.totpCredentialIdBytes();
        return context.network.account.getSecondAuthMethods(config.peergosUsername, context.signer).join()
                .stream()
                .anyMatch(m -> Arrays.equals(m.credentialId, ours));
    }

    /** True for definitive "your password / second factor isn't accepted" failures from signIn —
     *  used to distinguish from transient network/server errors so we only tear down the
     *  mount when the credentials themselves are the problem. "Unknown credential id" is what
     *  the server says when the user has revoked this mount's factor from another device. */
    private static boolean isCredentialFailure(Throwable t) {
        while (t != null) {
            String msg = t.getMessage();
            if (msg != null && (msg.contains("Incorrect username or password")
                    || msg.contains("Incorrect password")
                    || msg.contains("Unknown credential id")
                    || msg.contains("Server rejected second factor auth")
                    || msg.contains(REVOKED_ERROR))) {
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
     * Build a non-interactive MFA responder that answers with a code generated from the mount's
     * own stored secret. It replies with the stored credentialId without consulting the offered
     * methods: a mount's own factor is deliberately never offered as a login option, and the
     * server identifies the factor being answered by its credentialId anyway. If the user has
     * revoked it, the server rejects it and verifyCredentials tears the mount down.
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
            try {
                String code = totp.generateOneTimePasswordString(
                        new SecretKeySpec(secret, TotpKey.ALGORITHM), Instant.now());
                return Futures.of(new MultiFactorAuthResponse(credentialId, Either.a(code)));
            } catch (Exception e) {
                throw new RuntimeException("Failed to generate mount code", e);
            }
        };
    }

    /** Hand the mount's second factor back before we forget its secret, so unmounting doesn't
     *  leave a credential on the account that nothing can ever use or identify. Best effort: an
     *  unreachable server must not stop the user unmounting, it just leaves the factor for them
     *  to delete from 2FA settings.
     */
    private void revokeMountCredential(MountConfig config) {
        if (! config.hasTotp())
            return;
        try {
            // the running mount is already logged in; only fall back to signing in if it isn't
            UserContext context = activeContext.get();
            if (context == null)
                context = buildContext(config);
            context.network.account.deleteSecondFactor(config.peergosUsername,
                    config.totpCredentialIdBytes(), context.signer).join();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to revoke this mount's second factor, it will need "
                    + "deleting by hand from 2FA settings: " + rootMessage(e), e);
        }
    }

    private void disableMount() {
        ScheduledFuture<?> check = credentialCheck.getAndSet(null);
        if (check != null) check.cancel(false);
        backend.disable();
        activeContext.set(null);
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

    /** Whether the webdav server could still be started on this port, probed where it binds. */
    private static boolean isFree(int port) {
        try (java.net.ServerSocket s = new java.net.ServerSocket()) {
            s.setReuseAddress(true);
            s.bind(new java.net.InetSocketAddress("127.0.0.1", port));
            return true;
        } catch (IOException taken) {
            return false;
        }
    }

    private static void openInFileExplorer(String mountPoint) throws IOException {
        // Android isn't handled here — the host app exposes a JS bridge
        // (MainActivity.openMountInFiles) that fires a SAF browse intent with the
        // necessary Context. The web UI calls that bridge directly and never hits
        // this endpoint on Android.

        // Under flatpak gio mounts in the host's namespace, so the mount point doesn't
        // exist inside the sandbox at all: Desktop.open would reject it as missing, and
        // a sandboxed xdg-open would resolve it against our empty view. Open it on the
        // host, for the same reason we mount there.
        if (System.getenv("FLATPAK_ID") != null) {
            runOpener("flatpak-spawn", "--host", "xdg-open", mountPoint);
            return;
        }
        if (java.awt.Desktop.isDesktopSupported()) {
            java.awt.Desktop desktop = java.awt.Desktop.getDesktop();
            if (desktop.isSupported(java.awt.Desktop.Action.OPEN)) {
                desktop.open(new File(mountPoint));
                return;
            }
        }
        // Headless Linux JVMs (no AWT) — Desktop reports unsupported; xdg-open handles it.
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("linux")) {
            runOpener("xdg-open", mountPoint);
            return;
        }
        throw new IOException("No native file explorer available on this platform: " + os);
    }

    private static void runOpener(String... cmd) throws IOException {
        Process p = Runtime.getRuntime().exec(cmd);
        try {
            if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() != 0)
                throw new IOException(cmd[0] + " exited " + p.exitValue());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void handle(HttpExchange exchange) {
        long t1 = System.currentTimeMillis();
        String path = exchange.getRequestURI().getPath();
        try {
            if (path.startsWith("/"))
                path = path.substring(1);
            String action = path.substring(Constants.MOUNT.length());

            if (action.equals("get-config")) {
                MountConfig config = readConfig();
                Optional<String> activeMountPoint = backend.activeMountPoint();
                // a calendar only login is live with no drive, so the session is what says we are
                // on, and the mount point only says whether a drive came with it
                boolean sessionActive = activeContext.get() != null;
                String mountPoint = activeMountPoint.orElse("");
                Map<String, Object> json = new LinkedHashMap<>();
                json.put("enabled", sessionActive || config.enabled);
                json.put("mountDrive", config.mountDrive);
                json.put("syncCalendar", config.syncCalendar);
                json.put("syncContacts", config.syncContacts);
                json.put("peergosUsername", sessionActive ? activePeergosUsername.get() : config.peergosUsername);
                json.put("webdavUsername", config.webdavUsername);
                // A CalDAV or CardDAV client has to be given these by hand, and this endpoint is
                // already loopback only. The password is a token generated for the bridge, not
                // the user's Peergos password.
                json.put("webdavPassword", config.webdavPassword);
                json.put("webdavPort", config.webdavPort);
                json.put("davUrl", "http://localhost:" + config.webdavPort + "/dav/");
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
                // saveConfig only keeps a password it was given, so enabling without one
                // persists a mount that claims to be on and can never log in again
                if (peergosUsername == null || peergosUsername.isEmpty()
                        || peergosPassword == null || peergosPassword.isEmpty())
                    throw new IllegalStateException("Mounting needs your username and password");
                boolean autoMount = body.get("autoMount") instanceof Boolean ? (Boolean) body.get("autoMount") : true;
                // a client from before the split only ever asked for the drive
                boolean mountDrive = body.get("mountDrive") instanceof Boolean ? (Boolean) body.get("mountDrive") : true;
                boolean syncCalendar = body.get("syncCalendar") instanceof Boolean && (Boolean) body.get("syncCalendar");
                boolean syncContacts = body.get("syncContacts") instanceof Boolean && (Boolean) body.get("syncContacts");
                if (! mountDrive && ! syncCalendar && ! syncContacts)
                    throw new IllegalStateException("Choose at least one of the drive, calendar or contacts");
                String authType = "digest";
                // Optional TOTP credential supplied by the UI when the user had 2FA enabled.
                // Both hex-encoded; empty/missing means the mount logs in with password only.
                String totpCredentialId = (String) body.getOrDefault("totpCredentialId", "");
                String totpSecret       = (String) body.getOrDefault("totpSecret", "");

                disableMount();
                // Re-mounting the same account keeps the previous endpoint while it is free, so a
                // mount left behind by a kill is the one being asked for and gets adopted. A fresh
                // port would leave that mount in place and stack a second drive beside it, and the
                // webdav credentials have to come with it: the OS mount still speaks the old ones.
                MountConfig previous = readConfig();
                boolean sameEndpoint = !previous.webdavUsername.isEmpty()
                        && previous.peergosUsername.equals(peergosUsername)
                        && isFree(previous.webdavPort);
                String webdavUsername = sameEndpoint ? previous.webdavUsername : generateToken();
                String webdavPassword = sameEndpoint ? previous.webdavPassword : generateToken();
                int webdavPort = sameEndpoint ? previous.webdavPort : findFreePort();
                MountConfig config = new MountConfig(true, mountDrive, syncCalendar, syncContacts,
                        peergosUsername, peergosPassword,
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

            } else if (action.equals("open")) {
                if ("The Android Project".equals(System.getProperty("java.vm.vendor"))) {
                    exchange.sendResponseHeaders(200, 0);
                    return;
                }
                Optional<String> activeMountPoint = backend.activeMountPoint();
                if (activeMountPoint.isEmpty()) {
                    exchange.sendResponseHeaders(409, 0);
                    return;
                }
                openInFileExplorer(activeMountPoint.get());
                exchange.sendResponseHeaders(200, 0);

            } else if (action.equals("disable")) {
                // Capture the config before tearing down so we know whose keyring entries to
                // clear; verifyCredentials' transient disables don't take this path, so they
                // leave keyring entries intact for the next re-mount.
                MountConfig previous = readConfig();
                String username = previous.peergosUsername;
                revokeMountCredential(previous);
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
