package peergos.server.util.secrets;

import java.io.IOException;
import java.util.Optional;

/**
 * MacOS {@link SecretStore} backed by {@code /usr/bin/security}. Each entry
 * lives in the default login keychain as a generic-password item keyed by
 * {@code (service, account)}.
 *
 * First read from a freshly codesigned binary triggers a one-time "allow"
 * prompt in the Keychain UI; subsequent reads are silent once the user clicks
 * "Always Allow".
 */
public final class MacOsKeychainStore extends SubprocessSecretStore {

    @Override
    public void put(String service, String account, String value) throws IOException {
        // -U updates if an entry already exists, else creates one.
        run("security", "add-generic-password",
                "-U",
                "-s", service,
                "-a", account,
                "-w", value);
    }

    @Override
    public Optional<String> get(String service, String account) throws IOException {
        // -w prints just the password; non-zero exit means "not found".
        try {
            String out = run("security", "find-generic-password",
                    "-s", service,
                    "-a", account,
                    "-w");
            // `security -w` appends a trailing newline.
            if (out.endsWith("\n")) out = out.substring(0, out.length() - 1);
            return Optional.of(out);
        } catch (IOException notFound) {
            return Optional.empty();
        }
    }

    @Override
    public void delete(String service, String account) {
        // Best-effort: ignore "not found".
        runOrNull("security", "delete-generic-password",
                "-s", service,
                "-a", account);
    }

    @Override
    public boolean isAvailable() {
        // `security` is part of the macOS base install; presence of the binary
        // is enough — securityd is always running on a logged-in user session.
        return new java.io.File("/usr/bin/security").exists();
    }
}
