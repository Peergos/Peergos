package peergos.server.tests;

import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;
import peergos.server.*;
import peergos.server.space.*;
import peergos.server.sql.*;

import java.util.*;

@RunWith(Parameterized.class)
public class QuotaStoreTests {

    private final JdbcQuotas store;

    public QuotaStoreTests(JdbcQuotas store) {
        this.store = store;
    }

    @Parameterized.Parameters()
    public static Collection<Object[]> parameters() {
        JdbcQuotas ram = JdbcQuotas.build(Main.buildEphemeralSqlite(), new SqliteCommands());
        return Arrays.asList(new Object[][] {
                {ram}
        });
    }

    @Test
    public void updatesAndCount() {
        String bob = "bob";
        long oneG = 1024 * 1024 * 1024L;
        store.setQuota(bob, oneG);
        Assert.assertTrue(store.getQuota(bob) == oneG);
        store.setQuota(bob, 50 * oneG);
        Assert.assertTrue(store.getQuota(bob) == 50 * oneG);

        Assert.assertTrue(store.numberOfUsers() == 1);
        store.setQuota("fred", oneG);
        Assert.assertTrue(store.numberOfUsers() == 2);
    }

    @Test
    public void tokens() {
        String token = "bob";
        store.addToken(token);
        Assert.assertTrue(store.hasToken(token));
        store.removeToken(token);
        Assert.assertFalse(store.hasToken(token));
    }
}
