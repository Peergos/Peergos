package peergos.server.util.secrets;

import java.io.IOException;
import java.util.Optional;

/**
 * Linux {@link SecretStore} for the Flatpak sandbox, backed by the
 * {@code secret-tool} CLI (libsecret). Talks to the user's Freedesktop
 * Secret Service via D-Bus through the sandbox's {@code org.freedesktop.secrets}
 * portal grant.
 *
 * Selected only when {@code FLATPAK_ID} is set. Non flatpak Linux installs use
 * {@link JsonFileSecretStore} instead.
 */
public final class FlatpakSecretToolStore extends SubprocessSecretStore {

    private static final String SCHEMA = "org.peergos.Mount";

    @Override
    public void put(String service, String account, String value) throws IOException {
        // `secret-tool store` reads the password from stdin.
        runWithStdin(value,
                "secret-tool", "store",
                "--label=Peergos Mount (" + service + ":" + account + ")",
                "service", service,
                "account", account,
                "schema", SCHEMA);
    }

    @Override
    public Optional<String> get(String service, String account) throws IOException {
        try {
            String out = run("secret-tool", "lookup",
                    "service", service,
                    "account", account,
                    "schema", SCHEMA);
            // `secret-tool lookup` prints the value with no trailing newline,
            // but be defensive in case that changes.
            if (out.endsWith("\n")) out = out.substring(0, out.length() - 1);
            return out.isEmpty() ? Optional.empty() : Optional.of(out);
        } catch (IOException notFound) {
            return Optional.empty();
        }
    }

    @Override
    public void delete(String service, String account) {
        // `secret-tool clear` exits non-zero if nothing matched — that's fine for us.
        runOrNull("secret-tool", "clear",
                "service", service,
                "account", account,
                "schema", SCHEMA);
    }

    @Override
    public boolean isAvailable() {
        // Two checks: the binary is on PATH AND the Secret Service responds.
        // `search` exits 0 with no output when nothing matches; non-zero exit
        // means the daemon couldn't be reached (or the portal isn't granted).
        if (runOrNull("secret-tool", "--version") == null)
            return false;
        return runOrNull("secret-tool", "search",
                "service", "__peergos_keyring_probe__",
                "schema", SCHEMA) != null;
    }
}
