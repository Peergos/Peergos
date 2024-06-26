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
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.logging.*;
import java.util.stream.*;

public class ServerMessageStore implements ServerMessager {
    private static final Logger LOG = Logging.LOG();

    private static final String SELECT = "SELECT id, type, sent, body, priorid, dismissed FROM messages WHERE username = ?;";
    private static final String SELECT_AFTER = "SELECT username, id, type, sent, body, priorid, dismissed FROM messages WHERE sent >= ?;";
    private static final String ADD = "INSERT INTO messages (username, sent, type, body, priorid) VALUES(?, ?, ?, ?, ?);";
    private static final String DISMISS = "UPDATE messages SET dismissed = true WHERE id = ? AND username = ?;";
    private static final String COUNT = "SELECT COUNT (username) FROM messages where username = ? AND sent > ?;";

    private static final int HOUR_MILLIS = 3_600_000;

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
        List<ServerMessage> all = getMessages(username);
        List<ServerConversation> allConvs = ServerConversation.combine(all);
        List<ServerMessage> live = allConvs.stream()
                .filter(c -> c.isDisplayable)
                .flatMap(c -> c.messages.stream())
                .sorted()
                .collect(Collectors.toList());
        return Futures.of(live);
    }

    @Override
    public CompletableFuture<Boolean> sendMessage(String username, byte[] signedBody) {
        PublicKeyHash signerHash = pki.getPublicKeyHash(username).join().get();
        Optional<PublicSigningKey> signerOpt = ipfs.getSigningKey(signerHash, signerHash).join();
        if (! signerOpt.isPresent())
            throw new IllegalStateException("Couldn't retrieve signer key!");
        byte[] raw = signerOpt.get().unsignMessage(signedBody).join();
        CborObject cbor = CborObject.fromByteArray(raw);
        ServerMessage message = ServerMessage.fromCbor(cbor);
        switch (message.type) {
            case FromUser:
                if (Math.abs(message.sentEpochMillis - System.currentTimeMillis()) > HOUR_MILLIS)
                    return Futures.errored(new IllegalStateException("Invalid send time on message!"));
                long count = recentMessages(username);
                if (count > 20)
                    return Futures.errored(new IllegalStateException("Please wait before sending more messages!"));
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

    public List<Pair<String, ServerMessage>> getMessagesAfter(LocalDateTime after) {
        try (Connection conn = getConnection();
             PreparedStatement select = conn.prepareStatement(SELECT_AFTER)) {
            select.clearParameters();
            select.setLong(1, after.toEpochSecond(ZoneOffset.UTC)* 1_000);
            List<Pair<String, ServerMessage>> msgs = new ArrayList<>();
            ResultSet res = select.executeQuery();
            while (res.next()) {
                String username = res.getString(1);
                boolean dismissed = res.getBoolean(7);
                long priorIdRaw = res.getLong(6);
                Optional<Long> priorId = priorIdRaw == -1L ? Optional.empty() : Optional.of(priorIdRaw);
                msgs.add(new Pair<>(username, new ServerMessage(res.getLong(2), ServerMessage.Type.byValue(res.getInt(3)),
                        res.getLong(4), res.getString(5), priorId, dismissed)));
            }
            return msgs;
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new IllegalStateException(sqe);
        }
    }

    public List<ServerMessage> getMessages(String username) {
        try (Connection conn = getConnection();
             PreparedStatement select = conn.prepareStatement(SELECT)) {
            select.clearParameters();
            select.setString(1, username);
            List<ServerMessage> msgs = new ArrayList<>();
            ResultSet res = select.executeQuery();
            while (res.next()) {
                boolean dismissed = res.getBoolean(6);
                long priorIdRaw = res.getLong(5);
                Optional<Long> priorId = priorIdRaw == -1L ? Optional.empty() : Optional.of(priorIdRaw);
                msgs.add(new ServerMessage(res.getLong(1), ServerMessage.Type.byValue(res.getInt(2)),
                        res.getLong(3), res.getString(4), priorId, dismissed));
            }
            return msgs;
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new IllegalStateException(sqe);
        }
    }

    public long recentMessages(String username) {
        try (Connection conn = getConnection();
             PreparedStatement count = conn.prepareStatement(COUNT)) {
            count.clearParameters();
            count.setString(1, username);
            count.setLong(2, System.currentTimeMillis() - HOUR_MILLIS);
            ResultSet res = count.executeQuery();
            res.next();
            return res.getLong(1);
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
            insert.setLong(5, message.replyToId.orElse(-1L));
            insert.executeUpdate();
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new IllegalStateException(sqe);
        }
    }

    public void dismissMessage(String username, ServerMessage message) {
        try (Connection conn = getConnection();
             PreparedStatement dismiss = conn.prepareStatement(DISMISS)) {
            dismiss.clearParameters();
            dismiss.setLong(1, message.id);
            dismiss.setString(2, username);
            dismiss.executeUpdate();
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
