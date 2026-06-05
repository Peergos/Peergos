package peergos.server.util.secrets;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Shared {@link Process} helpers for {@link SecretStore} implementations that
 * wrap a command-line keyring tool (currently macOS {@code security} and
 * Linux {@code secret-tool}). Centralised so subclasses don't each reinvent
 * stdin piping, timeouts, and exit-code handling.
 */
abstract class SubprocessSecretStore implements SecretStore {

    /** Process timeout — keyring tools should complete in milliseconds. */
    private static final long TIMEOUT_SECONDS = 10;

    /** Run a command, returning stdout; throws on non-zero exit. */
    protected static String run(String... cmd) throws IOException {
        return runWithStdin(null, cmd);
    }

    /** Run a command, optionally piping {@code stdin} (without a trailing newline). */
    protected static String runWithStdin(String stdin, String... cmd) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        Process p = pb.start();
        if (stdin != null) {
            try (OutputStream os = p.getOutputStream()) {
                os.write(stdin.getBytes(StandardCharsets.UTF_8));
            }
        } else {
            p.getOutputStream().close();
        }
        try {
            if (!p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("Timed out waiting for: " + String.join(" ", cmd));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            throw new IOException("Interrupted waiting for: " + String.join(" ", cmd));
        }
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.exitValue() != 0)
            throw new IOException("Command failed (exit " + p.exitValue() + "): "
                    + String.join(" ", cmd) + "\n" + out);
        return out;
    }

    /** Like {@link #run} but returns {@code null} on a non-zero exit instead of throwing. */
    protected static String runOrNull(String... cmd) {
        try {
            return run(cmd);
        } catch (IOException e) {
            return null;
        }
    }
}
