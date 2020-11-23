package peergos.server.space;

import peergos.server.*;
import peergos.server.corenode.*;
import peergos.server.sql.*;
import peergos.server.storage.*;
import peergos.server.storage.admin.*;
import peergos.server.util.*;
import peergos.shared.corenode.*;
import peergos.shared.mutable.*;

import java.sql.*;
import java.util.*;
import java.util.function.*;

public class QuotaCLI extends Builder {

    private static QuotaAdmin buildQuotaStore(Args a) {
        TransactionStore transactions = buildTransactionStore(a);
        DeletableContentAddressedStorage localStorage = buildLocalStorage(a, transactions);
        JdbcIpnsAndSocial rawPointers = buildRawPointers(a);
        MutablePointers localPointers = UserRepository.build(localStorage, rawPointers);
        MutablePointersProxy proxingMutable = new HttpMutablePointers(buildP2pHttpProxy(a), getPkiServerId(a));
        CoreNode core = buildCorenode(a, localStorage, transactions, rawPointers, localPointers, proxingMutable);
        return buildSpaceQuotas(a, localStorage, core);
    }

    private static void printQuota(String name, long quota) {
        long mb = quota / 1024 / 1024;
        if (mb > 1024)
            System.out.println(name + " " + mb + " MiB");
        else
            System.out.println(name + " " + mb/1024 + " GiB");
    }

    public static final Command<Boolean> SET = new Command<>("set",
            "Set free quota for a user on this server",
            a -> {
                boolean paidStorage = a.hasArg("quota-admin-address");
                if (paidStorage)
                    throw new IllegalStateException("Quota CLI only valid on non paid instances");
                SqlSupplier sqlCommands = getSqlCommands(a);
                Supplier<Connection> quotasDb = getDBConnector(a, "quotas-sql-file");
                JdbcQuotas quotas = JdbcQuotas.build(quotasDb, sqlCommands);

                String name = a.getArg("username");
                long quota = UserQuotas.parseQuota(a.getArg("quota"));
                quotas.setQuota(name, quota);
                return true;
            },
            Arrays.asList(
                    new Command.Arg("username", "The username to set the quota of", true),
                    new Command.Arg("quota", "The quota in bytes or (k, m, g, t)", true),
                    new Command.Arg("quotas-sql-file", "The filename for the quotas datastore", true, "quotas.sql")
            )
    );

    public static final Command<Boolean> SHOW = new Command<>("show",
            "Show free quota of all users on this server",
            a -> {
                boolean paidStorage = a.hasArg("quota-admin-address");
                if (paidStorage)
                    throw new IllegalStateException("Quota CLI only valid on non paid instances");
                SqlSupplier sqlCommands = getSqlCommands(a);
                Supplier<Connection> quotasDb = getDBConnector(a, "quotas-sql-file");
                JdbcQuotas quotas = JdbcQuotas.build(quotasDb, sqlCommands);

                if (a.hasArg("username")) {
                    String name = a.getArg("username");
                    printQuota(name, quotas.getQuota(name));
                    return true;
                }
                TreeMap<String, Long> all = new TreeMap<>(quotas.getQuotas());
                all.forEach(QuotaCLI::printQuota);
                return true;
            },
            Arrays.asList(
                    new Command.Arg("username", "The user whose quota to show (or all users are shown)", false),
                    new Command.Arg("quotas-sql-file", "The filename for the quotas datastore", true, "quotas.sql")
            )
    );

    public static final Command<Boolean> QUOTA = new Command<>("quota",
            "Manage free quota of users on this server",
            args -> {
                System.out.println("Run with -help to show options");
                return null;
            },
            Arrays.asList(
                    new Command.Arg("print-log-location", "Whether to print the log file location at startup", false, "false"),
                    new Command.Arg("log-to-file", "Whether to log to a file", false, "false"),
                    new Command.Arg("log-to-console", "Whether to log to the console", false, "false")
            ),
            Arrays.asList(SHOW, SET)
    );
}
