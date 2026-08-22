package peergos.server.login;

import com.eatthepath.otp.*;
import com.webauthn4j.*;
import com.webauthn4j.data.client.*;
import peergos.server.sql.*;
import peergos.server.util.Logging;
import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.login.*;
import peergos.shared.login.mfa.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import javax.crypto.spec.*;
import java.nio.charset.*;
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
    private static final String REMOVE = "DELETE FROM login WHERE username=?;";
    private static final String UPDATE = "UPDATE login SET entry=?, reader=? WHERE username = ?";
    private static final String GET_LOGIN = "SELECT * FROM login WHERE username = ? AND reader = ? LIMIT 1;";
    private static final String GET = "SELECT * FROM login WHERE username = ? LIMIT 1;";
    private static final String CREATE_PENDING = "INSERT INTO pendinglogin (username, entry, reader, writer, target, created) VALUES(?, ?, ?, ?, ?, ?)";
    private static final String UPDATE_PENDING = "UPDATE pendinglogin SET entry=?, writer=?, target=?, created=? WHERE username = ? AND reader = ?";
    private static final String GET_PENDING = "SELECT * FROM pendinglogin WHERE username = ? AND reader = ? LIMIT 1;";
    private static final String GET_ALL_PENDING = "SELECT * FROM pendinglogin WHERE username = ?;";
    private static final String COUNT_PENDING = "SELECT COUNT(*) FROM pendinglogin WHERE username = ?;";
    private static final String REMOVE_PENDING = "DELETE FROM pendinglogin WHERE username=?;";
    private static final String CREATE_MFA = "INSERT INTO mfa (username, name, credid, type, enabled, created, value) VALUES(?, ?, ?, ?, ?, ?, ?);";
    private static final String UPDATE_MFA = "UPDATE mfa SET value=? WHERE username = ? AND credid = ?;";
    private static final String BURN_BACKUP_CODE = "UPDATE mfa SET value=?, name=? WHERE username = ? AND credid = ? AND value = ?;";
    private static final String GET_TYPE = "SELECT type FROM mfa WHERE username = ? AND credid = ?;";
    private static final String GET_AUTH = "SELECT value FROM mfa WHERE username = ? AND credid = ?;";
    private static final String CREATE_CHALLENGE = "INSERT INTO mfa_challenge (challenge, username) VALUES(?, ?);";
    private static final String UPDATE_CHALLENGE = "UPDATE mfa_challenge SET challenge=? WHERE username=?;";
    private static final String GET_CHALLENGE = "SELECT challenge FROM mfa_challenge WHERE username = ?;";
    private static final String ENABLE_AUTH = "UPDATE mfa SET enabled=? WHERE username = ? AND credid = ?;";
    private static final String DELETE_AUTH = "DELETE FROM mfa WHERE username = ? AND credid = ?";
    private static final String GET_AUTH_METHODS = "SELECT name, credid, created, type, enabled FROM mfa WHERE username = ?;";
    private static final String GET_IDS_OF_TYPE = "SELECT credid FROM mfa WHERE username = ? AND type = ?;";
    private static final String COUNT_MFA = "SELECT COUNT(*) FROM mfa WHERE username = ?;";
    private static final String DELETE_UNVERIFIED = "DELETE FROM mfa WHERE username = ? AND type = ? AND enabled = ?;";

    public static final int MAX_MFA = 10;
    /** A staged login is only left behind by a password change that was interrupted between staging and
     *  updating the login table. They are all cleared by the next successful login data write, so this
     *  should never be reached in practice. We refuse to stage more rather than deleting the oldest,
     *  because we can't tell from here which of them (if any) is the one being logged in with.
     */
    public static final int MAX_PENDING_LOGINS = 20;

    /** Tells us whether a staged password change has published its new WriterData, which is the point
     *  at which its login data becomes the live one.
     */
    public interface PublishedChecker {
        boolean isPublished(PublicKeyHash writer, Cid target);
    }

    private volatile boolean isClosed;
    private volatile Optional<PublishedChecker> published = Optional.empty();
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
            commands.createTable(commands.createPendingAccountTableCommand(), conn);
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
        boolean written;
        if (hasEntry(login.username)) {
            try (Connection conn = getConnection();
                 PreparedStatement insert = conn.prepareStatement(UPDATE)) {
                conn.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);

                insert.setString(1, new String(Base64.getEncoder().encode(login.entryPoints.serialize())));
                insert.setString(2, new String(Base64.getEncoder().encode(login.authorisedReader.serialize())));
                insert.setString(3, login.username);
                int changed = insert.executeUpdate();
                written = changed > 0;
            } catch (SQLException sqe) {
                LOG.log(Level.WARNING, sqe.getMessage(), sqe);
                throw new RuntimeException(sqe);
            }
        } else {
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(CREATE)) {
                stmt.setString(1, login.username);
                stmt.setString(2, new String(Base64.getEncoder().encode(login.entryPoints.serialize())));
                stmt.setString(3, new String(Base64.getEncoder().encode(login.authorisedReader.serialize())));
                stmt.executeUpdate();
                written = true;
            } catch (SQLException sqe) {
                LOG.log(Level.WARNING, sqe.getMessage(), sqe);
                throw new RuntimeException(sqe);
            }
        }
        if (written) // any staged password change is now either published here, or superseded
            clearPendingLoginData(login.username);
        return CompletableFuture.completedFuture(written);
    }

    /** Supply the means to tell whether staged login data has been published, so that a password change
     *  interrupted before updating the login table can be completed on the next read.
     */
    public void setPublishedChecker(PublishedChecker checker) {
        this.published = Optional.of(checker);
    }

    /** Store login data for a password change whose new key generation algorithm hasn't been published yet.
     *  This is additive - it never invalidates the login data currently in use.
     *
     * @param writer the identity key which the new WriterData is committed under
     * @param target the hash of the WriterData carrying the new key generation algorithm
     */
    public CompletableFuture<Boolean> setPendingLoginData(LoginData login, PublicKeyHash writer, Cid target) {
        String entry = new String(Base64.getEncoder().encode(login.entryPoints.serialize()));
        String reader = new String(Base64.getEncoder().encode(login.authorisedReader.serialize()));
        String writerString = new String(Base64.getEncoder().encode(writer.serialize()));
        if (hasPendingEntry(login.username, reader)) {
            // a retry of the same password change
            try (Connection conn = getConnection();
                 PreparedStatement update = conn.prepareStatement(UPDATE_PENDING)) {
                update.setString(1, entry);
                update.setString(2, writerString);
                update.setString(3, target.toString());
                update.setLong(4, System.currentTimeMillis());
                update.setString(5, login.username);
                update.setString(6, reader);
                return CompletableFuture.completedFuture(update.executeUpdate() > 0);
            } catch (SQLException sqe) {
                LOG.log(Level.WARNING, sqe.getMessage(), sqe);
                throw new RuntimeException(sqe);
            }
        }
        if (countPendingEntries(login.username) >= MAX_PENDING_LOGINS)
            throw new IllegalStateException("Too many incomplete password changes for " + login.username);
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(CREATE_PENDING)) {
            stmt.setString(1, login.username);
            stmt.setString(2, entry);
            stmt.setString(3, reader);
            stmt.setString(4, writerString);
            stmt.setString(5, target.toString());
            stmt.setLong(6, System.currentTimeMillis());
            stmt.executeUpdate();
            return CompletableFuture.completedFuture(true);
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    /** Complete any password change for this user that published its new WriterData, but was interrupted
     *  before its login data made it into the login table.
     *
     * @return true if the login table was updated
     */
    public boolean completeInterruptedPasswordChange(String username) {
        if (published.isEmpty())
            return false;
        List<LoginData> candidates = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(GET_ALL_PENDING)) {
            stmt.setString(1, username);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                PublicKeyHash writer = PublicKeyHash.fromCbor(CborObject.fromByteArray(Base64.getDecoder().decode(rs.getString("writer"))));
                if (! published.get().isPublished(writer, Cid.decode(rs.getString("target"))))
                    continue;
                candidates.add(new LoginData(username,
                        UserStaticData.fromCbor(CborObject.fromByteArray(Base64.getDecoder().decode(rs.getString("entry")))),
                        PublicSigningKey.fromCbor(CborObject.fromByteArray(Base64.getDecoder().decode(rs.getString("reader")))),
                        Optional.empty()));
            }
        } catch (Exception e) {
            // healing is best effort - the staged login data is still served directly
            LOG.log(Level.WARNING, e.getMessage(), e);
            return false;
        }
        if (candidates.isEmpty())
            return false;
        // only one WriterData is current, so at most one staged login can be the published one
        LoginData live = candidates.get(0);
        LOG.info("Completing an interrupted password change for " + username);
        setLoginData(live).join();
        return true;
    }

    private void clearPendingLoginData(String username) {
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(REMOVE_PENDING)) {
            stmt.setString(1, username);
            stmt.executeUpdate();
        } catch (SQLException sqe) {
            // Stale staged login data is harmless - it is unreachable without the matching password
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
        }
    }

    private boolean hasPendingEntry(String username, String reader) {
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(GET_PENDING)) {
            stmt.setString(1, username);
            stmt.setString(2, reader);
            return stmt.executeQuery().next();
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    private int countPendingEntries(String username) {
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(COUNT_PENDING)) {
            stmt.setString(1, username);
            ResultSet rs = stmt.executeQuery();
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    @Override
    public CompletableFuture<Boolean> removeLoginData(String username) {
        try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(REMOVE)) {
                stmt.setString(1, username);
                stmt.executeUpdate();
                clearPendingLoginData(username);
                return CompletableFuture.completedFuture(true);
            } catch (SQLException sqe) {
                LOG.log(Level.WARNING, sqe.getMessage(), sqe);
                return CompletableFuture.completedFuture(false);
            }
    }

    private MultiFactorAuthMethod.Type getType(String username, byte[] credentialId) {
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(GET_TYPE)) {
            stmt.setString(1, username);
            stmt.setBytes(2, credentialId);
            ResultSet resultSet = stmt.executeQuery();
            if (resultSet.next()) {
                return MultiFactorAuthMethod.Type.byValue(resultSet.getInt(1));
            }
            throw new IllegalStateException("Unknown credential id for user " + username);
        } catch (Exception e) {
            LOG.log(Level.WARNING, e.getMessage(), e);
            throw new IllegalStateException(e);
        }
    }

    public CompletableFuture<Either<UserStaticData, MultiFactorAuthRequest>> getEntryData(String username,
                                                                                          PublicSigningKey authorisedReader,
                                                                                          Optional<MultiFactorAuthResponse> mfa) {
        List<MultiFactorAuthMethod> mfas = getSecondAuthMethods(username).join();
        List<MultiFactorAuthMethod> enabled = mfas.stream().filter(m -> m.enabled).collect(Collectors.toList());
        // backup codes are never a second factor on their own, only a way of satisfying an existing one,
        // and a mount's credential belongs to a device rather than to the user, so neither of them is
        // something we can challenge a person with
        if (enabled.stream().allMatch(m -> m.type == MultiFactorAuthMethod.Type.BACKUP_CODES || ! m.type.interactive))
            return getEntryData(username, authorisedReader).thenApply(Either::a);
        if (mfa.isEmpty()) {
            byte[] challenge = createChallenge(username);
            return Futures.of(Either.b(new MultiFactorAuthRequest(enabled, challenge)));
        }
        MultiFactorAuthResponse mfaAuth = mfa.get();
        byte[] credentialId = mfaAuth.credentialId;
        if (mfaAuth.response.isB()) {
            MultiFactorAuthMethod.Type type = getType(username, credentialId);
            if (type != MultiFactorAuthMethod.Type.WEBAUTHN)
                throw new IllegalStateException("Not a webauthn credential!");
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
            MultiFactorAuthMethod.Type type = getType(username, credentialId);
            if (type == MultiFactorAuthMethod.Type.BACKUP_CODES)
                validateBackupCode(username, credentialId, mfaAuth.response.a());
            else if (type == MultiFactorAuthMethod.Type.TOTP || type == MultiFactorAuthMethod.Type.MOUNT)
                validateTotpCode(username, credentialId, mfaAuth.response.a());
            else
                throw new IllegalStateException("Not a code based credential!");
        }
        return getEntryData(username, authorisedReader).thenApply(Either::a);
    }

    public CompletableFuture<UserStaticData> getEntryData(String username, PublicSigningKey authorisedReader) {
        String reader = new String(Base64.getEncoder().encode(authorisedReader.serialize()));
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(GET_LOGIN)) {
            stmt.setString(1, username);
            stmt.setString(2, reader);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return CompletableFuture.completedFuture(UserStaticData.fromCbor(CborObject.fromByteArray(Base64.getDecoder().decode(rs.getString("entry")))));
            }
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            return Futures.errored(sqe);
        }

        // A password change that published its new key generation algorithm, but was interrupted before
        // updating the login table, is served from here. Only the matching password can derive this reader.
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(GET_PENDING)) {
            stmt.setString(1, username);
            stmt.setString(2, reader);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                LOG.info("Serving login data for " + username + " from an incomplete password change");
                UserStaticData entry = UserStaticData.fromCbor(CborObject.fromByteArray(Base64.getDecoder().decode(rs.getString("entry"))));
                // finish that password change now, rather than leaving the login table stale until the next one
                completeInterruptedPasswordChange(username);
                return CompletableFuture.completedFuture(entry);
            }

            return Futures.errored(new IllegalStateException("Incorrect username or password"));
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            return Futures.errored(sqe);
        }
    }

    public Optional<LoginData> getLoginData(String username) {
        // this is what gets mirrored and migrated, so make sure it isn't a password change out of date
        completeInterruptedPasswordChange(username);
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
                if ((type == MultiFactorAuthMethod.Type.TOTP || type == MultiFactorAuthMethod.Type.MOUNT) && !enabled)
                    continue; // Don't return a code based factor that was never verified
                String name = rs.getString("name");
                if (type == MultiFactorAuthMethod.Type.BACKUP_CODES && name.equals("0"))
                    continue; // an exhausted set is no longer a login option
                res.add(new MultiFactorAuthMethod(
                        name,
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
        // TOTP don't need names as there is only 1 active at a time
        return addCodeFactor(username, "", MultiFactorAuthMethod.Type.TOTP);
    }

    /** Add a code based second factor belonging to a device mount. Unlike a TOTP these are named,
     *  and any number of them can be active at once - one per device the user has mounted from.
     */
    public CompletableFuture<TotpKey> addMountFactor(String username, String name) {
        return addCodeFactor(username, name, MultiFactorAuthMethod.Type.MOUNT);
    }

    private CompletableFuture<TotpKey> addCodeFactor(String username, String name, MultiFactorAuthMethod.Type type) {
        if (name.length() > MultiFactorAuthMethod.MAX_NAME_LENGTH)
            throw new IllegalStateException("Second factor names must be smaller than "
                    + (MultiFactorAuthMethod.MAX_NAME_LENGTH + 1) + " characters");
        // an unverified factor of this type is an abandoned setup attempt - drop it rather than
        // letting cancelled attempts accumulate rows that count against the limit forever
        deleteUnverified(username, type);
        if (countMfa(username) >= MAX_MFA)
            throw new IllegalStateException("Too many multi factor auth methods. Please delete some.");
        byte[] rawKey = new byte[32];
        rnd.nextBytes(rawKey);
        byte[] credId = new byte[32];
        rnd.nextBytes(credId);
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(CREATE_MFA)) {
            stmt.setString(1, username);
            stmt.setString(2, name);
            stmt.setBytes(3, credId);
            stmt.setInt(4, type.value);
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

    private void deleteUnverified(String username, MultiFactorAuthMethod.Type type) {
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(DELETE_UNVERIFIED)) {
            stmt.setString(1, username);
            stmt.setInt(2, type.value);
            stmt.setBoolean(3, false);
            stmt.executeUpdate();
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new IllegalStateException(sqe);
        }
    }

    private int countMfa(String username) {
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(COUNT_MFA)) {
            stmt.setString(1, username);
            ResultSet rs = stmt.executeQuery();
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new IllegalStateException(sqe);
        }
    }

    /** Generate a fresh set of single use backup codes, invalidating any existing set.
     *  These are never created automatically - a user has to ask for them, and they are only
     *  allowed once there is another second factor for them to be a backup for.
     */
    public CompletableFuture<BackupCodes> generateBackupCodes(String username) {
        List<MultiFactorAuthMethod> existing = getSecondAuthMethods(username).join();
        boolean hasRealFactor = existing.stream()
                .anyMatch(m -> m.enabled && m.type != MultiFactorAuthMethod.Type.BACKUP_CODES);
        if (! hasRealFactor)
            throw new IllegalStateException("Please set up an authenticator app or security key before generating backup codes.");

        List<String> codes = new ArrayList<>();
        while (codes.size() < BackupCodes.CODE_COUNT) {
            byte[] random = new byte[BackupCodes.CODE_BYTES];
            rnd.nextBytes(random);
            String code = BackupCodes.generate(random);
            if (! codes.contains(code))
                codes.add(code);
        }
        byte[] credId = new byte[32];
        rnd.nextBytes(credId);

        // there is only ever 1 set of backup codes, generating replaces any earlier set
        List<byte[]> older = getBackupCodeIds(username);
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(CREATE_MFA)) {
            stmt.setString(1, username);
            stmt.setString(2, Integer.toString(codes.size())); // backup codes carry their remaining count in the name
            stmt.setBytes(3, credId);
            stmt.setInt(4, MultiFactorAuthMethod.Type.BACKUP_CODES.value);
            stmt.setBoolean(5, true);
            stmt.setLong(6, LocalDate.now().toEpochDay());
            stmt.setBytes(7, serializeCodeHashes(codes.stream()
                    .map(JdbcAccount::hashBackupCode)
                    .collect(Collectors.toList())));
            stmt.executeUpdate();
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new IllegalStateException(sqe);
        }
        for (byte[] oldId : older) {
            deleteMfa(username, oldId).join();
        }
        return Futures.of(new BackupCodes(credId, codes));
    }

    /** All backup code sets for a user, including any exhausted ones, which are hidden from
     *  getSecondAuthMethods.
     */
    private List<byte[]> getBackupCodeIds(String username) {
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(GET_IDS_OF_TYPE)) {
            stmt.setString(1, username);
            stmt.setInt(2, MultiFactorAuthMethod.Type.BACKUP_CODES.value);
            ResultSet rs = stmt.executeQuery();
            List<byte[]> res = new ArrayList<>();
            while (rs.next()) {
                res.add(rs.getBytes("credid"));
            }
            return res;
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    private static byte[] hashBackupCode(String code) {
        return Hash.sha256(BackupCodes.normalise(code).getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] serializeCodeHashes(List<byte[]> hashes) {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("h", new CborObject.CborList(hashes.stream()
                .map(CborObject.CborByteArray::new)
                .collect(Collectors.toList())));
        return CborObject.CborMap.build(state).serialize();
    }

    private static List<byte[]> parseCodeHashes(byte[] value) {
        CborObject.CborMap m = (CborObject.CborMap) CborObject.fromByteArray(value);
        return m.getList("h", c -> ((CborObject.CborByteArray) c).value);
    }

    /** Check a backup code and burn it. The remaining codes are written back with a compare and
     *  set on the old value, so two concurrent logins can't both redeem the same code.
     */
    private void validateBackupCode(String username, byte[] credentialId, String code) {
        byte[] hash = hashBackupCode(code);
        for (int attempt = 0; attempt < 2; attempt++) {
            byte[] current = getMfa(username, credentialId);
            List<byte[]> unused = parseCodeHashes(current);
            List<byte[]> remaining = unused.stream()
                    .filter(h -> ! MessageDigest.isEqual(h, hash))
                    .collect(Collectors.toList());
            if (remaining.size() == unused.size())
                throw new IllegalStateException("Invalid backup code for credId " + ArrayOps.bytesToHex(credentialId));
            if (burnBackupCode(username, credentialId, current, remaining))
                return;
        }
        throw new IllegalStateException("Concurrent modification of backup codes for " + username);
    }

    private boolean burnBackupCode(String username, byte[] credentialId, byte[] oldValue, List<byte[]> remaining) {
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(BURN_BACKUP_CODE)) {
            stmt.setBytes(1, serializeCodeHashes(remaining));
            stmt.setString(2, Integer.toString(remaining.size()));
            stmt.setString(3, username);
            stmt.setBytes(4, credentialId);
            stmt.setBytes(5, oldValue);
            return stmt.executeUpdate() == 1;
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
        // only one totp is ever active: enabling a new one replaces whatever the user had before
        List<MultiFactorAuthMethod> olderTotp = getSecondAuthMethods(username).join()
                .stream()
                .filter(m -> !Arrays.equals(m.credentialId, credentialId) && m.type == MultiFactorAuthMethod.Type.TOTP)
                .collect(Collectors.toList());
        return enableCodeFactor(username, credentialId, code, MultiFactorAuthMethod.Type.TOTP, olderTotp);
    }

    /** Unlike a totp, a mount factor replaces nothing: every mounted device has its own, and the
     *  user's authenticator app is a different type entirely, so neither disturbs the other.
     */
    public CompletableFuture<Boolean> enableMountFactor(String username, byte[] credentialId, String code) {
        return enableCodeFactor(username, credentialId, code, MultiFactorAuthMethod.Type.MOUNT,
                Collections.emptyList());
    }

    private CompletableFuture<Boolean> enableCodeFactor(String username,
                                                        byte[] credentialId,
                                                        String code,
                                                        MultiFactorAuthMethod.Type expected,
                                                        List<MultiFactorAuthMethod> toReplace) {
        MultiFactorAuthMethod.Type actual = getType(username, credentialId);
        if (actual != expected)
            throw new IllegalStateException("Second factor " + ArrayOps.bytesToHex(credentialId)
                    + " is not a " + expected + "!");
        validateTotpCode(username, credentialId, code);
        try (Connection conn = getConnection();
             PreparedStatement update = conn.prepareStatement(ENABLE_AUTH)) {
            update.setBoolean(1, true);
            update.setString(2, username);
            update.setBytes(3, credentialId);
            update.executeUpdate();
            // now delete any existing old ones
            for (MultiFactorAuthMethod mfa : toReplace) {
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

            // backup codes only exist to back up a factor the user logs in with, so don't outlive
            // the last one. A mount's credential isn't one of those - it never prompts anybody.
            List<MultiFactorAuthMethod> remaining = getSecondAuthMethods(username).join();
            if (remaining.stream().noneMatch(m -> m.enabled && m.type.interactive
                    && m.type != MultiFactorAuthMethod.Type.BACKUP_CODES)) {
                for (byte[] backupId : getBackupCodeIds(username)) {
                    deleteMfa(username, backupId).join();
                }
            }
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
