package peergos.server.space;

import peergos.server.*;
import peergos.server.sql.*;

import java.sql.*;
import java.util.*;
import java.util.function.*;

public class UsageCLI extends Builder {

    public static final Command<Boolean> SHOW = new Command<>("show",
            "Show usage for a user(s) on this server",
            a -> {
                SqlSupplier sqlCommands = getSqlCommands(a);
                Supplier<Connection> usageDb = getDBConnector(a, "space-usage-sql-file");
                JdbcUsageStore usage = new JdbcUsageStore(usageDb, sqlCommands);

                if (a.hasArg("username")) {
                    String name = a.getArg("username");
                    printUsage(name, usage.getUsage(name).totalUsage());
                    return true;
                }
                usage.getAllUsage()
                        .entrySet()
                        .stream()
                        .sorted(Comparator.comparingLong(Map.Entry::getValue))
                        .forEach(e -> printUsage(e.getKey(), e.getValue()));
                return true;
            },
            Arrays.asList(
                    new Command.Arg("username", "The user whose quota to show (or all users are shown)", false),
                    new Command.Arg("space-usage-sql-file", "The filename for the space usage datastore", true, "quotas.sql")
            )
    );

    private static void printUsage(String name, long usage) {
        System.out.println(name + " " + formatUsage(usage));
    }

    private static String formatUsage(long quota) {
        long mb = quota / 1000_000;
        if (mb == 0)
            return quota + " B";
        if (mb < 1000)
            return mb + " MB";
        return mb/1000 + " GB";
    }

    public static final Command<Boolean> USAGE = new Command<>("usage",
            "Show usage on this server",
            args -> {
                System.out.println("Run with -help to show options");
                return null;
            },
            Arrays.asList(
                    new Command.Arg("print-log-location", "Whether to print the log file location at startup", false, "false"),
                    new Command.Arg("log-to-file", "Whether to log to a file", false, "false"),
                    new Command.Arg("log-to-console", "Whether to log to the console", false, "false")
            ),
            Arrays.asList(SHOW)
    );
}
