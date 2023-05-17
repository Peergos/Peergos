package peergos.server.login;

import com.eatthepath.otp.*;
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
    private static final String CREATE_AUTH = "INSERT INTO mfa (username, uid, type, enabled, value) VALUES(?, ?, ?, ?, ?)";
    private static final String GET_AUTH = "SELECT value FROM mfa WHERE username = ? AND uid = ?;";
    private static final String UPDATE_AUTH = "UPDATE mfa SET enabled=? WHERE username = ? AND uid = ?";
    private static final String GET_AUTH_METHODS = "SELECT uid, type, enabled FROM mfa WHERE username = ?;";

    private volatile boolean isClosed;
    private Supplier<Connection> conn;
    private final SecureRandom rnd = new SecureRandom();

    public JdbcAccount(Supplier<Connection> conn, SqlSupplier commands) {
        this.conn = conn;
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

    public CompletableFuture<Either<UserStaticData, List<MultiFactorAuthMethod>>> getEntryData(String username,
                                                                                               PublicSigningKey authorisedReader,
                                                                                               Optional<MultiFactorAuthResponse> mfa) {
        List<MultiFactorAuthMethod> mfas = getSecondAuthMethods(username).join();
        List<MultiFactorAuthMethod> enabled = mfas.stream().filter(m -> m.enabled).collect(Collectors.toList());
        if (enabled.isEmpty())
            return getEntryData(username, authorisedReader).thenApply(Either::a);
        if (mfa.isEmpty())
            return Futures.of(Either.b(enabled));
        MultiFactorAuthResponse mfaAuth = mfa.get();
        validateTotpCode(username, mfaAuth.uid, mfaAuth.code);
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
                res.add(new MultiFactorAuthMethod(rs.getString("uid"),
                        MultiFactorAuthMethod.Type.byValue(rs.getInt("type")),
                        rs.getBoolean("enabled")));
            }

            return Futures.of(res);
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    public CompletableFuture<TotpKey> addTotpFactor(String username, byte[] auth) {
        byte[] rawKey = new byte[32];
        rnd.nextBytes(rawKey);
        try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(CREATE_AUTH)) {
                stmt.setString(1, username);
                stmt.setString(2, UUID.randomUUID().toString());
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

    private void validateTotpCode(String username, String uid, String code) {
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(GET_AUTH)) {
            stmt.setString(1, username);
            stmt.setString(2, uid);
            ResultSet resultSet = stmt.executeQuery();
            if (resultSet.next()) {
                byte[] rawKey = resultSet.getBytes("value");
                String algorithm = "HmacSHA1";
                TimeBasedOneTimePasswordGenerator totp = new TimeBasedOneTimePasswordGenerator(Duration.ofSeconds(30L), 6, algorithm);
                Key key = new SecretKeySpec(rawKey, algorithm);
                String serverCode = totp.generateOneTimePasswordString(key, Instant.now());
                if (!serverCode.equals(code))
                    throw new IllegalStateException("Invalid TOTP code");
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, e.getMessage(), e);
            throw new IllegalStateException(e);
        }
    }

    public CompletableFuture<Boolean> enableTotpFactor(String username, String uid, String code) {
        validateTotpCode(username, uid, code);
        try (Connection conn = getConnection();
             PreparedStatement update = conn.prepareStatement(UPDATE_AUTH)) {
            update.setBoolean(1, true);
            update.setString(2, username);
            update.setString(3, uid);
            update.executeUpdate();

            return Futures.of(false);
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
