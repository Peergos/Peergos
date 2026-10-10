package peergos.server.login;

import peergos.server.sql.*;
import peergos.server.util.Logging;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.util.*;

import java.nio.charset.*;
import java.security.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.util.logging.*;

/** Limits how many guesses can be made at a user's password and second factor.
 *
 *  Only distinct guesses count, so retyping the same wrong password or code doesn't use up the budget.
 *  A device which has logged in before presents a token, and gets a budget of its own. So somebody
 *  guessing can lock out logins from new devices, but never from a device the user already uses.
 *
 *  A guess is recorded before it is checked, and removed again if it was right, so parallel requests
 *  can't get past the limit between the check and the record.
 */
public class LoginThrottle {
    private static final Logger LOG = Logging.LOG();

    public static final String THROTTLED_ERROR = "Too many failed login attempts. Please try again later, or from a device you have logged in on before.";
    public static final int MAX_PASSWORD_FAILURES = 10;
    public static final int MAX_MFA_FAILURES = 10;
    public static final long FAILURE_WINDOW_MILLIS = 24 * 3600_000L;
    public static final long DEVICE_TOKEN_TTL_MILLIS = 180 * 24 * 3600_000L;
    public static final int MAX_DEVICE_TOKENS = 20;
    private static final long PRUNE_INTERVAL_MILLIS = 3600_000L;
    private static final String UNTRUSTED = "-";

    private static final String COUNT = "SELECT COUNT(*) FROM login_failures WHERE username = ? AND bucket = ? AND time > ?;";
    private static final String HAS = "SELECT 1 FROM login_failures WHERE username = ? AND bucket = ? AND attempt = ?;";
    private static final String RESERVE = "INSERT INTO login_failures (username, bucket, attempt, time) SELECT ?, ?, ?, ? " +
            "WHERE (SELECT COUNT(*) FROM login_failures WHERE username = ? AND bucket = ? AND time > ?) < ?;";
    private static final String RELEASE = "DELETE FROM login_failures WHERE username = ? AND bucket = ? AND attempt = ?;";
    private static final String PRUNE_FAILURES = "DELETE FROM login_failures WHERE time <= ?;";
    private static final String GET_TOKEN = "SELECT 1 FROM device_tokens WHERE username = ? AND token = ? AND lastused > ?;";
    private static final String CREATE_TOKEN = "INSERT INTO device_tokens (username, token, lastused) VALUES(?, ?, ?);";
    private static final String TOUCH_TOKEN = "UPDATE device_tokens SET lastused = ? WHERE username = ? AND token = ?;";
    private static final String LIMIT_TOKENS = "DELETE FROM device_tokens WHERE username = ? AND token NOT IN " +
            "(SELECT token FROM device_tokens WHERE username = ? ORDER BY lastused DESC LIMIT ?);";
    private static final String PRUNE_TOKENS = "DELETE FROM device_tokens WHERE lastused <= ?;";

    public static final class Device {
        private final Optional<String> token;

        private Device(Optional<String> token) {
            this.token = token;
        }

        public boolean isTrusted() {
            return token.isPresent();
        }

        private String bucketId() {
            return token.map(t -> hash(t).substring(0, 16)).orElse(UNTRUSTED);
        }
    }

    private final Supplier<Connection> conn;
    private final SecureRandom rnd = new SecureRandom();
    private final AtomicLong lastPrune = new AtomicLong(0);

    public LoginThrottle(Supplier<Connection> conn, SqlSupplier commands) {
        this.conn = conn;
        try (Connection c = conn.get()) {
            commands.createTable(commands.createLoginFailuresTableCommand(), c);
            commands.createTable(commands.createDeviceTokensTableCommand(), c);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** An invalid or expired token is treated the same as none at all. */
    public Device device(String username, Optional<String> token) {
        if (token.isEmpty())
            return new Device(Optional.empty());
        try (Connection c = conn.get();
             PreparedStatement stmt = c.prepareStatement(GET_TOKEN)) {
            stmt.setString(1, username);
            stmt.setString(2, hash(token.get()));
            stmt.setLong(3, System.currentTimeMillis() - DEVICE_TOKEN_TTL_MILLIS);
            return new Device(stmt.executeQuery().next() ? token : Optional.empty());
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            return new Device(Optional.empty());
        }
    }

    /** Called after a successful login. Returns the token the device should present next time. */
    public Optional<String> loggedIn(String username, Device device) {
        long now = System.currentTimeMillis();
        try (Connection c = conn.get()) {
            if (device.token.isPresent()) {
                try (PreparedStatement stmt = c.prepareStatement(TOUCH_TOKEN)) {
                    stmt.setLong(1, now);
                    stmt.setString(2, username);
                    stmt.setString(3, hash(device.token.get()));
                    stmt.executeUpdate();
                }
                return device.token;
            }
            byte[] raw = new byte[32];
            rnd.nextBytes(raw);
            String token = ArrayOps.bytesToHex(raw);
            try (PreparedStatement stmt = c.prepareStatement(CREATE_TOKEN)) {
                stmt.setString(1, username);
                stmt.setString(2, hash(token));
                stmt.setLong(3, now);
                stmt.executeUpdate();
            }
            try (PreparedStatement stmt = c.prepareStatement(LIMIT_TOKENS)) {
                stmt.setString(1, username);
                stmt.setString(2, username);
                stmt.setInt(3, MAX_DEVICE_TOKENS);
                stmt.executeUpdate();
            }
            return Optional.of(token);
        } catch (SQLException sqe) {
            // the login itself has succeeded, the device just stays untrusted
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            return Optional.empty();
        }
    }

    /** Throws if this device has used up its password guesses, unless this is one it has already made. */
    public void reservePasswordAttempt(String username, Device device, PublicSigningKey reader) {
        if (! reserve(username, passwordBucket(device), readerId(reader), MAX_PASSWORD_FAILURES))
            throw new IllegalStateException(THROTTLED_ERROR);
    }

    public void releasePasswordAttempt(String username, Device device, PublicSigningKey reader) {
        release(username, passwordBucket(device), readerId(reader));
    }

    /** Second factor guesses only count once the password is known to be right, which keeps an attacker
     *  without it from filling the table with buckets for made up passwords. The budget is per device,
     *  not per password, so that a full one is no hint as to whether the password was right.
     */
    public void reserveMfaAttempt(String username, Device device, byte[] credentialId, String code) {
        if (! reserve(username, mfaBucket(device), codeId(credentialId, code), MAX_MFA_FAILURES))
            throw new IllegalStateException(THROTTLED_ERROR);
    }

    public void checkMfaAttempt(String username, Device device, byte[] credentialId, String code) {
        String bucket = mfaBucket(device);
        String attempt = codeId(credentialId, code);
        try (Connection c = conn.get()) {
            if (! has(c, username, bucket, attempt) && count(c, username, bucket) >= MAX_MFA_FAILURES)
                throw new IllegalStateException(THROTTLED_ERROR);
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new IllegalStateException(THROTTLED_ERROR);
        }
    }

    public void releaseMfaAttempt(String username, Device device, byte[] credentialId, String code) {
        release(username, mfaBucket(device), codeId(credentialId, code));
    }

    private static String passwordBucket(Device device) {
        return "pw:" + device.bucketId();
    }

    private static String mfaBucket(Device device) {
        return "mfa:" + device.bucketId();
    }

    private static String readerId(PublicSigningKey reader) {
        return hash(reader.serialize());
    }

    private static String codeId(byte[] credentialId, String code) {
        return hash(ArrayOps.concat(credentialId, code.getBytes(StandardCharsets.UTF_8)));
    }

    private boolean reserve(String username, String bucket, String attempt, int max) {
        pruneIfDue();
        // a serialization failure or a concurrent identical guess can make the insert fail, so retry
        for (int i = 0; i < 3; i++) {
            try (Connection c = conn.get()) {
                if (has(c, username, bucket, attempt))
                    return true;
                long now = System.currentTimeMillis();
                try (PreparedStatement stmt = c.prepareStatement(RESERVE)) {
                    stmt.setString(1, username);
                    stmt.setString(2, bucket);
                    stmt.setString(3, attempt);
                    stmt.setLong(4, now);
                    stmt.setString(5, username);
                    stmt.setString(6, bucket);
                    stmt.setLong(7, now - FAILURE_WINDOW_MILLIS);
                    stmt.setInt(8, max);
                    return stmt.executeUpdate() > 0;
                }
            } catch (SQLException sqe) {
                LOG.log(Level.INFO, "Retrying login attempt reservation: " + sqe.getMessage());
            }
        }
        return false;
    }

    private void release(String username, String bucket, String attempt) {
        try (Connection c = conn.get();
             PreparedStatement stmt = c.prepareStatement(RELEASE)) {
            stmt.setString(1, username);
            stmt.setString(2, bucket);
            stmt.setString(3, attempt);
            stmt.executeUpdate();
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
        }
    }

    private static boolean has(Connection c, String username, String bucket, String attempt) throws SQLException {
        try (PreparedStatement stmt = c.prepareStatement(HAS)) {
            stmt.setString(1, username);
            stmt.setString(2, bucket);
            stmt.setString(3, attempt);
            return stmt.executeQuery().next();
        }
    }

    private static int count(Connection c, String username, String bucket) throws SQLException {
        try (PreparedStatement stmt = c.prepareStatement(COUNT)) {
            stmt.setString(1, username);
            stmt.setString(2, bucket);
            stmt.setLong(3, System.currentTimeMillis() - FAILURE_WINDOW_MILLIS);
            ResultSet rs = stmt.executeQuery();
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private void pruneIfDue() {
        long now = System.currentTimeMillis();
        long last = lastPrune.get();
        if (now - last < PRUNE_INTERVAL_MILLIS || ! lastPrune.compareAndSet(last, now))
            return;
        try (Connection c = conn.get()) {
            try (PreparedStatement stmt = c.prepareStatement(PRUNE_FAILURES)) {
                stmt.setLong(1, now - FAILURE_WINDOW_MILLIS);
                stmt.executeUpdate();
            }
            try (PreparedStatement stmt = c.prepareStatement(PRUNE_TOKENS)) {
                stmt.setLong(1, now - DEVICE_TOKEN_TTL_MILLIS);
                stmt.executeUpdate();
            }
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
        }
    }

    private static String hash(String in) {
        return hash(in.getBytes(StandardCharsets.UTF_8));
    }

    private static String hash(byte[] in) {
        try {
            return ArrayOps.bytesToHex(MessageDigest.getInstance("SHA-256").digest(in));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
