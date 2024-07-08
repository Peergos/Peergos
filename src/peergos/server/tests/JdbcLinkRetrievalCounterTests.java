package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.space.*;
import peergos.server.sql.*;
import peergos.server.storage.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.storage.*;

import java.sql.*;
import java.time.*;
import java.util.*;

public class JdbcLinkRetrievalCounterTests {

    @Test
    public void basicUsage() throws Exception {
        Connection db = new Sqlite.UncloseableConnection(Sqlite.build(":memory:"));
        JdbcLinkRetrievalcounter store = new JdbcLinkRetrievalcounter(() -> db, new SqliteCommands());
        String name = "bob";
        LocalDateTime start = LocalDateTime.now();
        Thread.sleep(2_000);
        store.increment(name, 1);

        Assert.assertTrue(1 == store.getCount(name, 1));
        Assert.assertTrue(store.getLatestModificationTime(name).get().isBefore(LocalDateTime.now()));
        for (int i=0; i < 100; i++) {
            store.increment(name, 1);
            long count = store.getCount(name, 1);
            Assert.assertTrue(2 + i == count);
        }

        LinkCounts updatedCounts = store.getUpdatedCounts(name, start);
        Assert.assertTrue(updatedCounts.counts.containsKey(1L));

//        store.setCounts();
    }
}
