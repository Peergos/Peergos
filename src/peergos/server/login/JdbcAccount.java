package peergos.server.login;

import com.eatthepath.otp.*;
import com.webauthn4j.*;
import com.webauthn4j.data.attestation.statement.*;
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

public class JdbcAccount implements LoginCache {
    private static final Logger LOG = Logging.LOG();

    private static final String CREATE = "INSERT INTO login (username, entry, reader) VALUES(?, ?, ?)";
    private static final String UPDATE = "UPDATE login SET entry=?, reader=? WHERE username = ?";
    private static final String GET_LOGIN = "SELECT * FROM login WHERE username = ? AND reader = ? LIMIT 1;";
    private static final String GET = "SELECT * FROM login WHERE username = ? LIMIT 1;";
    private static final String CREATE_MFA = "INSERT INTO mfa (username, credid, type, enabled, value) VALUES(?, ?, ?, ?, ?);";
    private static final String UPDATE_MFA = "UPDATE mfa SET value=? WHERE username = ? AND credid = ?;";
    private static final String GET_AUTH = "SELECT value FROM mfa WHERE username = ? AND credid = ?;";
    private static final String CREATE_CHALLENGE = "INSERT INTO mfa_challenge (username, challenge) VALUES(?, ?);";
    private static final String GET_CHALLENGE = "SELECT challenge FROM mfa_challenge WHERE username = ?;";
    private static final String ENABLE_AUTH = "UPDATE mfa SET enabled=? WHERE username = ? AND credid = ?;";
    private static final String DELETE_AUTH = "DELETE FROM mfa WHERE username = ? AND credid = ?";
    private static final String GET_AUTH_METHODS = "SELECT credid, type, enabled FROM mfa WHERE username = ?;";

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
        if (mfaAuth.responseCbor instanceof CborObject.CborMap) {
            Webauthn.Verifier verifier = Webauthn.Verifier.fromCbor(CborObject.fromByteArray(getMfa(username, credentialId)));
            byte[] challenge = getChallenge(username);
            byte[] authenticatorData = ((CborObject.CborMap) mfaAuth.responseCbor).getByteArray("authenticatorData");
            byte[] clientDataJson = ((CborObject.CborMap) mfaAuth.responseCbor).getByteArray("clientDataJson");
            byte[] signature = ((CborObject.CborMap) mfaAuth.responseCbor).getByteArray("signature");
            long newSignCount = Webauthn.validateLogin(webauthn, origin, rpId, challenge, verifier, credentialId, username.getBytes(),
                    authenticatorData, clientDataJson, signature);
            // Update counter
            verifier.setCounter(newSignCount);
            updateMFA(username, credentialId, verifier.serialize());
        } else {
            if (!(mfaAuth.responseCbor instanceof CborObject.CborString))
                throw new IllegalArgumentException("totp code cbor is not a string!");
            validateTotpCode(username, credentialId, ((CborObject.CborString) mfaAuth.responseCbor).value);
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
                res.add(new MultiFactorAuthMethod(rs.getBytes("credid"),
                        MultiFactorAuthMethod.Type.byValue(rs.getInt("type")),
                        rs.getBoolean("enabled")));
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
            stmt.setBytes(2, credId);
            stmt.setInt(3, MultiFactorAuthMethod.Type.TOTP.value);
            stmt.setBoolean(4, false);
            stmt.setBytes(5, rawKey);
            stmt.executeUpdate();
            return Futures.of(new TotpKey(rawKey));
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

        String algorithm = "HmacSHA1";
        TimeBasedOneTimePasswordGenerator totp = new TimeBasedOneTimePasswordGenerator(Duration.ofSeconds(30L), 6, algorithm);
        Key key = new SecretKeySpec(rawKey, algorithm);
        try {
            String serverCode = totp.generateOneTimePasswordString(key, Instant.now());
            if (!serverCode.equals(code))
                throw new IllegalStateException("Invalid TOTP code");
        } catch (InvalidKeyException e) {
            throw new RuntimeException(e);
        }
    }

    public CompletableFuture<Boolean> enableTotpFactor(String username, byte[] credentialId, String code) {
        validateTotpCode(username, credentialId, code);
        try (Connection conn = getConnection();
             PreparedStatement update = conn.prepareStatement(ENABLE_AUTH)) {
            update.setBoolean(1, true);
            update.setString(2, username);
            update.setBytes(3, credentialId);
            update.executeUpdate();

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
        try (Connection conn = getConnection();
             PreparedStatement update = conn.prepareStatement(CREATE_CHALLENGE)) {
            update.setString(1, username);
            update.setBytes(2, challenge);
            update.executeUpdate();
            return challenge;
        } catch (Exception e) {
            LOG.log(Level.WARNING, e.getMessage(), e);
            throw new IllegalStateException(e);
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

    public void registerSecurityKeyComplete(String username, MultiFactorAuthResponse resp) {
        byte[] challenge = getChallenge(username);
        if (! (resp.responseCbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for MFA response!");
        byte[] attestationObject = ((CborObject.CborMap) resp.responseCbor).getByteArray("attestationObject");
        byte[] clientDataJson = ((CborObject.CborMap) resp.responseCbor).getByteArray("clientDataJson");
        Webauthn.Verifier authenticator = Webauthn.validateRegistration(webauthn, origin, rpId, challenge,
                attestationObject, clientDataJson);
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(CREATE_MFA)) {
            stmt.setString(1, username);
            stmt.setBytes(2, resp.credentialId);
            stmt.setInt(3, MultiFactorAuthMethod.Type.WEBAUTHN.value);
            stmt.setBoolean(4, true);
            stmt.setBytes(5, authenticator.serialize());
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
