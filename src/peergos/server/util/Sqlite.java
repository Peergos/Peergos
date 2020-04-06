package peergos.server.util;

import org.sqlite.*;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

public class Sqlite {

    public static Connection build(String dbPath) throws SQLException {
        String url = "jdbc:sqlite:"+dbPath;
        SQLiteDataSource dc = new SQLiteDataSource();
        dc.setUrl(url);

        Connection conn = dc.getConnection();
        conn.setAutoCommit(true);
        return conn;
    }

    public static String getDbPath(Args a, String type) {
        String sqlFile = a.getArg(type);
        return sqlFile.equals(":memory:") ? sqlFile : a.fromPeergosDir(type).toString();
    }

    public static class UncloseableConnection implements Connection {

        private final Connection target;

        public UncloseableConnection(Connection target) {
            this.target = target;
        }

        @Override
        public Statement createStatement() throws SQLException {
            return target.createStatement();
        }

        @Override
        public PreparedStatement prepareStatement(String s) throws SQLException {
            return target.prepareStatement(s);
        }

        @Override
        public CallableStatement prepareCall(String s) throws SQLException {
            return target.prepareCall(s);
        }

        @Override
        public String nativeSQL(String s) throws SQLException {
            return target.nativeSQL(s);
        }

        @Override
        public void setAutoCommit(boolean b) throws SQLException {
            target.setAutoCommit(b);
        }

        @Override
        public boolean getAutoCommit() throws SQLException {
            return target.getAutoCommit();
        }

        @Override
        public void commit() throws SQLException {
            target.commit();
        }

        @Override
        public void rollback() throws SQLException {
            target.rollback();
        }

        @Override
        public void close() throws SQLException {
            // Do nothing
        }

        @Override
        public boolean isClosed() throws SQLException {
            return false;
        }

        @Override
        public DatabaseMetaData getMetaData() throws SQLException {
            return target.getMetaData();
        }

        @Override
        public void setReadOnly(boolean b) throws SQLException {
            target.setReadOnly(b);
        }

        @Override
        public boolean isReadOnly() throws SQLException {
            return target.isReadOnly();
        }

        @Override
        public void setCatalog(String s) throws SQLException {
            target.setCatalog(s);
        }

        @Override
        public String getCatalog() throws SQLException {
            return target.getCatalog();
        }

        @Override
        public void setTransactionIsolation(int i) throws SQLException {
            target.setTransactionIsolation(i);
        }

        @Override
        public int getTransactionIsolation() throws SQLException {
            return target.getTransactionIsolation();
        }

        @Override
        public SQLWarning getWarnings() throws SQLException {
            return target.getWarnings();
        }

        @Override
        public void clearWarnings() throws SQLException {
            target.clearWarnings();
        }

        @Override
        public Statement createStatement(int i, int i1) throws SQLException {
            return target.createStatement(i, i1);
        }

        @Override
        public PreparedStatement prepareStatement(String s, int i, int i1) throws SQLException {
            return target.prepareStatement(s, i, i1);
        }

        @Override
        public CallableStatement prepareCall(String s, int i, int i1) throws SQLException {
            return target.prepareCall(s, i, i1);
        }

        @Override
        public Map<String, Class<?>> getTypeMap() throws SQLException {
            return target.getTypeMap();
        }

        @Override
        public void setTypeMap(Map<String, Class<?>> map) throws SQLException {
            target.setTypeMap(map);
        }

        @Override
        public void setHoldability(int i) throws SQLException {
            target.setHoldability(i);
        }

        @Override
        public int getHoldability() throws SQLException {
            return target.getHoldability();
        }

        @Override
        public Savepoint setSavepoint() throws SQLException {
            return target.setSavepoint();
        }

        @Override
        public Savepoint setSavepoint(String s) throws SQLException {
            return target.setSavepoint(s);
        }

        @Override
        public void rollback(Savepoint savepoint) throws SQLException {
            target.rollback(savepoint);
        }

        @Override
        public void releaseSavepoint(Savepoint savepoint) throws SQLException {
            target.releaseSavepoint(savepoint);
        }

        @Override
        public Statement createStatement(int i, int i1, int i2) throws SQLException {
            return target.createStatement(i, i1, i2);
        }

        @Override
        public PreparedStatement prepareStatement(String s, int i, int i1, int i2) throws SQLException {
            return target.prepareStatement(s, i, i1, i2);
        }

        @Override
        public CallableStatement prepareCall(String s, int i, int i1, int i2) throws SQLException {
            return target.prepareCall(s, i, i1, i2);
        }

        @Override
        public PreparedStatement prepareStatement(String s, int i) throws SQLException {
            return target.prepareStatement(s, i);
        }

        @Override
        public PreparedStatement prepareStatement(String s, int[] ints) throws SQLException {
            return target.prepareStatement(s, ints);
        }

        @Override
        public PreparedStatement prepareStatement(String s, String[] strings) throws SQLException {
            return target.prepareStatement(s, strings);
        }

        @Override
        public Clob createClob() throws SQLException {
            return target.createClob();
        }

        @Override
        public Blob createBlob() throws SQLException {
            return target.createBlob();
        }

        @Override
        public NClob createNClob() throws SQLException {
            return target.createNClob();
        }

        @Override
        public SQLXML createSQLXML() throws SQLException {
            return target.createSQLXML();
        }

        @Override
        public boolean isValid(int i) throws SQLException {
            return target.isValid(i);
        }

        @Override
        public void setClientInfo(String s, String s1) throws SQLClientInfoException {
            target.setClientInfo(s, s1);
        }

        @Override
        public void setClientInfo(Properties properties) throws SQLClientInfoException {
            target.setClientInfo(properties);
        }

        @Override
        public String getClientInfo(String s) throws SQLException {
            return target.getClientInfo(s);
        }

        @Override
        public Properties getClientInfo() throws SQLException {
            return target.getClientInfo();
        }

        @Override
        public Array createArrayOf(String s, Object[] objects) throws SQLException {
            return target.createArrayOf(s, objects);
        }

        @Override
        public Struct createStruct(String s, Object[] objects) throws SQLException {
            return target.createStruct(s, objects);
        }

        @Override
        public void setSchema(String s) throws SQLException {
            target.setSchema(s);
        }

        @Override
        public String getSchema() throws SQLException {
            return target.getSchema();
        }

        @Override
        public void abort(Executor executor) throws SQLException {
            target.abort(executor);
        }

        @Override
        public void setNetworkTimeout(Executor executor, int i) throws SQLException {
            target.setNetworkTimeout(executor, i);
        }

        @Override
        public int getNetworkTimeout() throws SQLException {
            return target.getNetworkTimeout();
        }

        @Override
        public <T> T unwrap(Class<T> aClass) throws SQLException {
            return target.unwrap(aClass);
        }

        @Override
        public boolean isWrapperFor(Class<?> aClass) throws SQLException {
            return target.isWrapperFor(aClass);
        }
    }
}
