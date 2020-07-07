package peergos.server.messages;

import peergos.server.sql.*;
import peergos.server.util.Logging;
import peergos.shared.cbor.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.logging.*;

public class ServerMessageStore implements ServerMessager {
    private static final Logger LOG = Logging.LOG();

    private static final String SELECT = "SELECT id, type, sent, body FROM messages WHERE username = ?;";
    private static final String ADD = "INSERT INTO messages (username, sent, type, body) VALUES(?, ?, ?, ?);";
    private static final String DISMISS = "DELETE FROM messages WHERE id = ? AND username = ?;";

    private final Supplier<Connection> conn;
    private final SqlSupplier commands;
    private final CoreNode pki;
    private final ContentAddressedStorage ipfs;
    private volatile boolean isClosed;

    public ServerMessageStore(Supplier<Connection> conn, SqlSupplier commands, CoreNode pki, ContentAddressedStorage ipfs) {
        this.conn = conn;
        this.commands = commands;
        this.pki = pki;
        this.ipfs = ipfs;
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
            commands.createTable(commands.createServerMessageTableCommand(), conn);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public CompletableFuture<List<ServerMessage>> getMessages(String username, byte[] auth) {
        return Futures.of(getMessages(username));
    }

    @Override
    public CompletableFuture<Boolean> sendMessage(String username, byte[] signedBody) {
        PublicKeyHash signerHash = pki.getPublicKeyHash(username).join().get();
        Optional<PublicSigningKey> signerOpt = ipfs.getSigningKey(signerHash).join();
        if (! signerOpt.isPresent())
            throw new IllegalStateException("Couldn't retrieve signer key!");
        byte[] raw = signerOpt.get().unsignMessage(signedBody);
        CborObject cbor = CborObject.fromByteArray(raw);
        ServerMessage message = ServerMessage.fromCbor(cbor);
        switch (message.type) {
            case FromUser:
                addMessage(username, message);
                break;
            case Dismiss:
                dismissMessage(username, message);
                break;
            default:
                throw new IllegalStateException("Invalid message type sent from user: " + message.type.name());
        }
        return Futures.of(true);
    }

    public List<ServerMessage> getMessages(String username) {
        try (Connection conn = getConnection();
             PreparedStatement insert = conn.prepareStatement(SELECT)) {
            insert.clearParameters();
            insert.setString(1, username);
            List<ServerMessage> msgs = new ArrayList<>();
            ResultSet res = insert.executeQuery();
            while (res.next())
                msgs.add(new ServerMessage(res.getLong(1), ServerMessage.Type.byValue(res.getInt(2)),
                        res.getLong(3), res.getString(4)));
            return msgs;
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new IllegalStateException(sqe);
        }
    }

    public void addMessage(String username, ServerMessage message) {
        try (Connection conn = getConnection();
             PreparedStatement insert = conn.prepareStatement(ADD)) {
            insert.clearParameters();
            insert.setString(1, username);
            insert.setLong(2, message.sentEpochMillis);
            insert.setLong(3, message.type.value);
            insert.setString(4, message.contents);
            insert.executeUpdate();
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new IllegalStateException(sqe);
        }
    }

    public void dismissMessage(String username, ServerMessage message) {
        try (Connection conn = getConnection();
             PreparedStatement delete = conn.prepareStatement(DISMISS)) {
            delete.clearParameters();
            delete.setLong(1, message.id);
            delete.setString(2, username);
            delete.executeUpdate();
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new IllegalStateException(sqe);
        }
    }

    public synchronized void close() {
        if (isClosed)
            return;
        isClosed = true;
    }
}
