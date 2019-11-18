package peergos.server.util;

import org.postgresql.Driver;

import java.sql.*;
import java.util.*;

public class Postgres {

    public static Connection build(String host,
                                   int port,
                                   String database,
                                   String user,
                                   String password) throws SQLException {
        Logging.LOG().warning("This postgres client is not using TLS you should be using a VPN to secure traffic!");
        Driver driver = new Driver();
        Properties props = new Properties();
        props.setProperty("user", user);
        props.setProperty("password", password);
        return driver.connect("jdbc:postgresql://" + host + ":" + port + "/" + database, props);
    }
}
