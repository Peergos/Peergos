package peergos.server.login;

import com.eatthepath.otp.*;
import com.webauthn4j.*;
import com.webauthn4j.data.client.*;
import peergos.server.sql.*;
import peergos.server.util.Logging;
import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.login.*;
import peergos.shared.login.mfa.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import javax.crypto.spec.*;
import java.security.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.logging.*;
import java.util.stream.*;

public class JdbcAccount {
    private static final Logger LOG = Logging.LOG();

    private static final String CREATE = "INSERT INTO login (username, entry, reader) VALUES(?, ?, ?)";
    private static final String UPDATE = "UPDATE login SET entry=?, reader=? WHERE username = ?";
    private static final String GET_LOGIN = "SELECT * FROM login WHERE username = ? AND reader = ? LIMIT 1;";
    private static final String GET = "SELECT * FROM login WHERE username = ? LIMIT 1;";
    private static final String CREATE_MFA = "INSERT INTO mfa (username, name, credid, type, enabled, created, value) VALUES(?, ?, ?, ?, ?, ?, ?);";
    private static final String UPDATE_MFA = "UPDATE mfa SET value=? WHERE username = ? AND credid = ?;";
    private static final String GET_AUTH = "SELECT value FROM mfa WHERE username = ? AND credid = ?;";
    private static final String CREATE_CHALLENGE = "INSERT INTO mfa_challenge (challenge, username) VALUES(?, ?);";
    private static final String UPDATE_CHALLENGE = "UPDATE mfa_challenge SET challenge=? WHERE username=?;";
    private static final String GET_CHALLENGE = "SELECT challenge FROM mfa_challenge WHERE username = ?;";
    private static final String ENABLE_AUTH = "UPDATE mfa SET enabled=? WHERE username = ? AND credid = ?;";
    private static final String DELETE_AUTH = "DELETE FROM mfa WHERE username = ? AND credid = ?";
    private static final String GET_AUTH_METHODS = "SELECT name, credid, created, type, enabled FROM mfa WHERE username = ?;";

    public static final int MAX_MFA = 10;

    private volatile boolean isClosed;
    private Supplier<Connection> conn;
    private final SecureRandom rnd = new SecureRandom();
    private final WebAuthnManager webauthn = WebAuthnManager.createNonStrictWebAuthnManager();
    private final Origin origin;
    private final String rpId;

    public JdbcAccount(Supplier<Connection> conn, SqlSupplier commands, Origin origin, String rpId) {
        this.conn = conn;
        this.origin = origin;
        this.rpId = rpId;
        init(commands);
    }

    private Connection getConnection() {
        Connection connection = conn.get();
        try {
            connection.setAutoCommit(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            return connection;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private synchronized void init(SqlSupplier commands) {
        if (isClosed)
            return;

        try (Connection conn = getConnection()) {
            commands.createTable(commands.createAccountTableCommand(), conn);
            commands.createTable(commands.createMfaTableCommand(), conn);
            commands.createTable(commands.createMfaChallengeTableCommand(), conn);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private boolean hasEntry(String username) {
        try (Connection conn = getConnection();
             PreparedStatement present = conn.prepareStatement(GET)) {
            conn.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            present.setString(1, username);
            ResultSet rs = present.executeQuery();
            if (rs.next()) {
                return true;
            }
            return false;
        } catch (SQLException sqe) {
                LOG.log(Level.WARNING, sqe.getMessage(), sqe);
                throw new RuntimeException(sqe);
            }
    }

    public CompletableFuture<Boolean> setLoginData(LoginData login) {
        if (hasEntry(login.username)) {
            try (Connection conn = getConnection();
                 PreparedStatement insert = conn.prepareStatement(UPDATE)) {
                conn.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);

                insert.setString(1, new String(Base64.getEncoder().encode(login.entryPoints.serialize())));
                insert.setString(2, new String(Base64.getEncoder().encode(login.authorisedReader.serialize())));
                insert.setString(3, login.username);
                int changed = insert.executeUpdate();
                return CompletableFuture.completedFuture(changed > 0);
            } catch (SQLException sqe) {
                LOG.log(Level.WARNING, sqe.getMessage(), sqe);
                return CompletableFuture.completedFuture(false);
            }
        } else {
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(CREATE)) {
                stmt.setString(1, login.username);
                stmt.setString(2, new String(Base64.getEncoder().encode(login.entryPoints.serialize())));
                stmt.setString(3, new String(Base64.getEncoder().encode(login.authorisedReader.serialize())));
                stmt.executeUpdate();
                return CompletableFuture.completedFuture(true);
            } catch (SQLException sqe) {
                LOG.log(Level.WARNING, sqe.getMessage(), sqe);
                return CompletableFuture.completedFuture(false);
            }
        }
    }

    public CompletableFuture<Either<UserStaticData, MultiFactorAuthRequest>> getEntryData(String username,
                                                                                          PublicSigningKey authorisedReader,
                                                                                          Optional<MultiFactorAuthResponse> mfa) {
        List<MultiFactorAuthMethod> mfas = getSecondAuthMethods(username).join();
        List<MultiFactorAuthMethod> enabled = mfas.stream().filter(m -> m.enabled).collect(Collectors.toList());
        if (enabled.isEmpty())
            return getEntryData(username, authorisedReader).thenApply(Either::a);
        if (mfa.isEmpty()) {
            byte[] challenge = createChallenge(username);
            return Futures.of(Either.b(new MultiFactorAuthRequest(enabled, challenge)));
        }
        MultiFactorAuthResponse mfaAuth = mfa.get();
        byte[] credentialId = mfaAuth.credentialId;
        if (mfaAuth.response.isB()) {
            Webauthn.Verifier verifier = Webauthn.Verifier.fromCbor(CborObject.fromByteArray(getMfa(username, credentialId)));
            byte[] challenge = getChallenge(username);
            byte[] authenticatorData = mfaAuth.response.b().authenticatorData;
            byte[] clientDataJson = mfaAuth.response.b().clientDataJson;
            byte[] signature = mfaAuth.response.b().signature;
            long newSignCount = Webauthn.validateLogin(webauthn, origin, rpId, challenge, verifier, credentialId, username.getBytes(),
                    authenticatorData, clientDataJson, signature);
            // Update counter
            verifier.setCounter(newSignCount);
            updateMFA(username, credentialId, verifier.serialize());
        } else {
            validateTotpCode(username, credentialId, mfaAuth.response.a());
        }
        return getEntryData(username, authorisedReader).thenApply(Either::a);
    }

    public CompletableFuture<UserStaticData> getEntryData(String username, PublicSigningKey authorisedReader) {
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(GET_LOGIN)) {
            stmt.setString(1, username);
            stmt.setString(2, new String(Base64.getEncoder().encode(authorisedReader.serialize())));
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return CompletableFuture.completedFuture(UserStaticData.fromCbor(CborObject.fromByteArray(Base64.getDecoder().decode(rs.getString("entry")))));
            }

            if (hasEntry(username))
                return Futures.errored(new IllegalStateException("Incorrect password"));
            return Futures.errored(new IllegalStateException("Unknown username. Did you enter it correctly?"));
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            return Futures.errored(sqe);
        }
    }

    public Optional<LoginData> getLoginData(String username) {
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(GET)) {
            stmt.setString(1, username);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                UserStaticData entry = UserStaticData.fromCbor(CborObject.fromByteArray(Base64.getDecoder().decode(rs.getString("entry"))));
                PublicSigningKey authorisedReader = PublicSigningKey.fromCbor(CborObject.fromByteArray(Base64.getDecoder().decode(rs.getString("reader"))));
                return Optional.of(new LoginData(username, entry, authorisedReader, Optional.empty()));
            }

            return Optional.empty();
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    public CompletableFuture<List<MultiFactorAuthMethod>> getSecondAuthMethods(String username) {
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(GET_AUTH_METHODS)) {
            stmt.setString(1, username);
            ResultSet rs = stmt.executeQuery();
            List<MultiFactorAuthMethod> res = new ArrayList<>();
            while (rs.next()) {
                boolean enabled = rs.getBoolean("enabled");
                MultiFactorAuthMethod.Type type = MultiFactorAuthMethod.Type.byValue(rs.getInt("type"));
                if (type == MultiFactorAuthMethod.Type.TOTP && !enabled)
                    continue; // Don't return disabled totp
                res.add(new MultiFactorAuthMethod(
                        rs.getString("name"),
                        rs.getBytes("credid"),
                        LocalDate.ofEpochDay(rs.getInt("created")),
                        type,
                        enabled));
            }

            return Futures.of(res);
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    public void updateMFA(String username, byte[] credentialId, byte[] value) {
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(UPDATE_MFA)) {
            stmt.setBytes(1, value);
            stmt.setString(2, username);
            stmt.setBytes(3, credentialId);
            stmt.executeUpdate();
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new IllegalStateException(sqe);
        }
    }

    public CompletableFuture<TotpKey> addTotpFactor(String username) {
        byte[] rawKey = new byte[32];
        rnd.nextBytes(rawKey);
        byte[] credId = new byte[32];
        rnd.nextBytes(credId);
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(CREATE_MFA)) {
            stmt.setString(1, username);
            stmt.setString(2, ""); // TOTP don't need names as there is only 1 active at a time
            stmt.setBytes(3, credId);
            stmt.setInt(4, MultiFactorAuthMethod.Type.TOTP.value);
            stmt.setBoolean(5, false);
            stmt.setLong(6, LocalDate.now().toEpochDay());
            stmt.setBytes(7, rawKey);
            stmt.executeUpdate();
            return Futures.of(new TotpKey(credId, rawKey));
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new IllegalStateException(sqe);
        }
    }

    private byte[] getMfa(String username, byte[] credentialId) {
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(GET_AUTH)) {
            stmt.setString(1, username);
            stmt.setBytes(2, credentialId);
            ResultSet resultSet = stmt.executeQuery();
            if (resultSet.next()) {
                return resultSet.getBytes("value");
            }
            throw new IllegalStateException("Unknown credential id for user " + username);
        } catch (Exception e) {
            LOG.log(Level.WARNING, e.getMessage(), e);
            throw new IllegalStateException(e);
        }
    }

    private void validateTotpCode(String username, byte[] credentialId, String code) {
        byte[] rawKey = getMfa(username, credentialId);

        TimeBasedOneTimePasswordGenerator totp = new TimeBasedOneTimePasswordGenerator(Duration.ofSeconds(30L), 6, TotpKey.ALGORITHM);
        Key key = new SecretKeySpec(rawKey, TotpKey.ALGORITHM);
        try {
            Instant now = Instant.now();
            String serverCode = totp.generateOneTimePasswordString(key, now);
            if (serverCode.equals(code))
                return;
            String previousCode = totp.generateOneTimePasswordString(key, now.minusSeconds(30));
            if (previousCode.equals(code))
                return;
            throw new IllegalStateException("Invalid TOTP code for credId " + ArrayOps.bytesToHex(credentialId));
        } catch (InvalidKeyException e) {
            throw new RuntimeException(e);
        }
    }

    public CompletableFuture<Boolean> enableTotpFactor(String username, byte[] credentialId, String code) {
        List<MultiFactorAuthMethod> olderTotp = getSecondAuthMethods(username).join()
                .stream()
                .filter(m -> !Arrays.equals(m.credentialId, credentialId) && m.type == MultiFactorAuthMethod.Type.TOTP)
                .collect(Collectors.toList());
        validateTotpCode(username, credentialId, code);
        try (Connection conn = getConnection();
             PreparedStatement update = conn.prepareStatement(ENABLE_AUTH)) {
            update.setBoolean(1, true);
            update.setString(2, username);
            update.setBytes(3, credentialId);
            update.executeUpdate();
            // now delete any existing old ones
            for (MultiFactorAuthMethod mfa : olderTotp) {
                deleteMfa(username, mfa.credentialId).join();
            }
            return Futures.of(true);
        } catch (Exception e) {
            LOG.log(Level.WARNING, e.getMessage(), e);
            throw new IllegalStateException(e);
        }
    }

    public byte[] registerSecurityKeyStart(String username) {
        List<MultiFactorAuthMethod> existing = getSecondAuthMethods(username).join();
        if (existing.size() > MAX_MFA)
            throw new IllegalStateException("Too many multi factor auth methods. Please delete some.");
        return createChallenge(username);
    }

    private byte[] createChallenge(String username) {
        byte[] challenge = new byte[32];
        rnd.nextBytes(challenge);
        boolean hasChallenge = hasChallenge(username);
        try (Connection conn = getConnection();
             PreparedStatement update = hasChallenge ? conn.prepareStatement(UPDATE_CHALLENGE) : conn.prepareStatement(CREATE_CHALLENGE)) {
            update.setBytes(1, challenge);
            update.setString(2, username);
            update.executeUpdate();
            return challenge;
        } catch (Exception e) {
            LOG.log(Level.WARNING, e.getMessage(), e);
            throw new IllegalStateException(e);
        }
    }

    private boolean hasChallenge(String username) {
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(GET_CHALLENGE)) {
            stmt.setString(1, username);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return true;
            }

            return false;
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    private byte[] getChallenge(String username) {
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(GET_CHALLENGE)) {
            stmt.setString(1, username);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getBytes("challenge");
            }

            throw new IllegalStateException("No challenge for " + username);
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    public void registerSecurityKeyComplete(String username, String keyName, MultiFactorAuthResponse resp) {
        if (keyName.length() > 32)
            throw new IllegalStateException("Max second factor name length is 32 characters");
        byte[] challenge = getChallenge(username);
        if (resp.response.isA())
            throw new IllegalStateException("Not MFA response!");
        byte[] attestationObject = resp.response.b().authenticatorData;
        byte[] clientDataJson = resp.response.b().clientDataJson;
        Webauthn.Verifier authenticator = Webauthn.validateRegistration(webauthn, origin, rpId, challenge,
                attestationObject, clientDataJson);
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(CREATE_MFA)) {
            stmt.setString(1, username);
            stmt.setString(2, keyName);
            stmt.setBytes(3, resp.credentialId);
            stmt.setInt(4, MultiFactorAuthMethod.Type.WEBAUTHN.value);
            stmt.setBoolean(5, true);
            stmt.setLong(6, LocalDate.now().toEpochDay());
            stmt.setBytes(7, authenticator.serialize());
            stmt.executeUpdate();
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new IllegalStateException(sqe);
        }
    }

    public CompletableFuture<Boolean> deleteMfa(String username, byte[] credentialId) {
        try (Connection conn = getConnection();
             PreparedStatement update = conn.prepareStatement(DELETE_AUTH)) {
            update.setString(1, username);
            update.setBytes(2, credentialId);
            update.executeUpdate();

            return Futures.of(true);
        } catch (Exception e) {
            LOG.log(Level.WARNING, e.getMessage(), e);
            throw new IllegalStateException(e);
        }
    }

    public synchronized void close() {
        if (isClosed)
            return;

        isClosed = true;
    }
}
