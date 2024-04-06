package peergos.server.storage;

import io.libp2p.core.*;
import io.libp2p.core.crypto.*;
import org.peergos.protocol.ipns.pb.*;
import peergos.server.sql.*;
import peergos.server.util.Logging;
import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.resolution.*;
import peergos.shared.storage.*;

import java.sql.*;
import java.sql.Connection;
import java.util.*;
import java.util.function.*;
import java.util.logging.*;

public class JdbcServerIdentityStore implements ServerIdentityStore {
	private static final Logger LOG = Logging.LOG();

    private static final String SELECT_PEERIDS = "SELECT peerid FROM serverids ORDER BY id;";
    private static final String SELECT_PRIVATE = "SELECT private FROM serverids WHERE peerid=?;";
    private static final String GET_RECORD = "SELECT record FROM serverids WHERE peerid=?;";
    private static final String SET_RECORD = "UPDATE serverids SET record=? WHERE peerid = ?;";
    private static final String SET_PRIVATE = "UPDATE serverids SET private=? WHERE peerid = ?;";

    private Supplier<Connection> conn;
    private final SqlSupplier commands;
    private final Crypto crypto;
    private volatile boolean isClosed;

    public JdbcServerIdentityStore(Supplier<Connection> conn, SqlSupplier commands, Crypto crypto) {
        this.conn = conn;
        this.commands = commands;
        this.crypto = crypto;
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
            commands.createTable(commands.createServerIdentitiesTableCommand(), conn);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void addIdentity(PeerId id, byte[] signedIpnsRecord) {
        try (Connection conn = getConnection();
             PreparedStatement insert = conn.prepareStatement(commands.insertServerIdCommand())) {
            insert.setBytes(1, id.getBytes());
            insert.setBytes(2, signedIpnsRecord);
            insert.executeUpdate();
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
        }
    }

    @Override
    public void setPrivateKey(PrivKey privateKey) {
        try (Connection conn = getConnection();
             PreparedStatement insert = conn.prepareStatement(SET_PRIVATE)) {
            insert.setBytes(1, privateKey.bytes());
            insert.setBytes(2, PeerId.fromPubKey(privateKey.publicKey()).getBytes());
            insert.executeUpdate();
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
        }
    }

    @Override
    public List<PeerId> getIdentities() {
        try (Connection conn = getConnection();
             PreparedStatement select = conn.prepareStatement(SELECT_PEERIDS)) {
            ResultSet qres = select.executeQuery();
            List<PeerId> res = new ArrayList<>();
            while (qres.next()) {
                res.add(new PeerId(qres.getBytes(1)));
            }
            return res;
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    @Override
    public byte[] getPrivateKey(PeerId peerId) {
        try (Connection conn = getConnection();
             PreparedStatement select = conn.prepareStatement(SELECT_PRIVATE)) {
            select.setBytes(1, peerId.getBytes());
            ResultSet qres = select.executeQuery();
            while (qres.next()) {
                return qres.getBytes(1);
            }
            throw new IllegalStateException("No id record for " + peerId);
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    @Override
    public byte[] getRecord(PeerId peerId) {
        try (Connection conn = getConnection();
             PreparedStatement select = conn.prepareStatement(GET_RECORD)) {
            select.setBytes(1, peerId.getBytes());
            ResultSet qres = select.executeQuery();
            while (qres.next()) {
                return qres.getBytes(1);
            }
            throw new IllegalStateException("No ipns record for " + peerId);
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    public ResolutionRecord getValue(IpnsEntry entry) {
        CborObject cbor = CborObject.fromByteArray(entry.data);
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for IpnsEntry!");
        CborObject.CborMap map = (CborObject.CborMap) cbor;
        return ResolutionRecord.fromCbor(CborObject.fromByteArray(map.getByteArray("Value")));
    }

    @Override
    public void setRecord(PeerId peerId, byte[] newRecord) {
        byte[] currentRaw = getRecord(peerId);
        try {
            Ipns.IpnsEntry currentEntry = Ipns.IpnsEntry.parseFrom(currentRaw);
            Ipns.IpnsEntry newEntry = Ipns.IpnsEntry.parseFrom(newRecord);
            IpnsEntry existing = new IpnsEntry(currentEntry.getSignatureV2().toByteArray(), currentEntry.getData().toByteArray());
            IpnsEntry updated = new IpnsEntry(newEntry.getSignatureV2().toByteArray(), newEntry.getData().toByteArray());
            ResolutionRecord existingValue = getValue(existing);
            ResolutionRecord updatedValue = getValue(updated);

            if (updatedValue.sequence != newEntry.getSequence())
                throw new IllegalStateException("Non matching sequence!");
            if (updated.getIpnsSequence() != newEntry.getSequence())
                throw new IllegalStateException("Non matching sequence!");
            updatedValue.ensureValidUpdateTo(existingValue);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        try (Connection conn = getConnection();
             PreparedStatement select = conn.prepareStatement(SET_RECORD)) {
            select.setBytes(1, newRecord);
            select.setBytes(2, peerId.getBytes());
            int updatedRows = select.executeUpdate();
            if (updatedRows != 1)
                throw new IllegalStateException("Set record failed for " + peerId);
        } catch (SQLException sqe) {
            LOG.log(Level.WARNING, sqe.getMessage(), sqe);
            throw new RuntimeException(sqe);
        }
    }

    public synchronized void close() {
        if (isClosed)
            return;
        isClosed = true;
    }

    public static JdbcServerIdentityStore build(Supplier<Connection> conn, SqlSupplier commands, Crypto crypto) {
        return new JdbcServerIdentityStore(conn, commands, crypto);
    }
}
