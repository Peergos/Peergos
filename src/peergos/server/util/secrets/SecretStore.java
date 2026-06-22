package peergos.server.util.secrets;

import java.io.IOException;
import java.util.Optional;

/**
 * A small abstraction over OS credential stores (MacOS Keychain, Windows Credential
 * Manager, Linux libsecret via the Freedesktop Secret Service). Implementations are
 * selected by {@link #detect()} based on the current platform.
 *
 * The service / account pair is the lookup key; the value is an opaque string the
 * caller chooses to interpret (we hex-encode binary secrets so transport stays
 * printable across keyring backends).
 */
public interface SecretStore {

    void put(String service, String account, String value) throws IOException;

    Optional<String> get(String service, String account) throws IOException;

    /** Delete; no-op if absent. */
    void delete(String service, String account) throws IOException;

    /**
     * True if this store keeps the secret inside {@code mount-config.json}
     * (i.e. {@link JsonFileSecretStore}). MountConfigHandler uses this to decide
     * whether to redact the password / TOTP fields before writing the config.
     */
    default boolean embedsInConfigFile() {
        return false;
    }

    /**
     * Cheap probe to confirm the underlying store is reachable (binary present,
     * D-Bus name resolvable, etc.). Returning false means {@link #get}/{@link #put}
     * will throw; callers should surface that to the user rather than silently
     * downgrade to a less secure store.
     */
    boolean isAvailable();

    /**
     * Pick the appropriate store for the current process:
     * <ul>
     *   <li>macOS → {@link MacOsKeychainStore}</li>
     *   <li>Windows → {@link WindowsCredentialManagerStore}</li>
     *   <li>Linux + {@code FLATPAK_ID} → {@link FlatpakSecretToolStore}</li>
     *   <li>Linux otherwise → {@link JsonFileSecretStore}</li>
     * </ul>
     */
    static SecretStore detect() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.startsWith("mac"))
            return new MacOsKeychainStore();
        if (os.startsWith("windows"))
            return new WindowsCredentialManagerStore();
        if (System.getenv("FLATPAK_ID") != null)
            return new FlatpakSecretToolStore();
        return new JsonFileSecretStore();
    }
}
