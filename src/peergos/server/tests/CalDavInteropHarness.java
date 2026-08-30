package peergos.server.tests;

import org.eclipse.jetty.server.Server;
import peergos.server.Builder;
import peergos.server.Main;
import peergos.server.util.Args;
import peergos.server.webdav.MountConfig;
import peergos.server.webdav.WebdavServer;
import peergos.shared.Crypto;
import peergos.shared.NetworkAccess;
import peergos.shared.user.App;
import peergos.shared.user.UserContext;
import peergos.shared.util.PathUtil;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * A throwaway Peergos instance with seeded calendars, fronted by the CalDAV bridge, that
 * stays up until you kill it. For the manual real-client matrix in section 6 of caldav.md:
 * point Thunderbird, Evolution, macOS Calendar or DAVx5 at the printed URL and see what
 * they actually do, which is the part the protocol tests cannot tell us.
 *
 * <pre>
 *   java -cp dist/Peergos.jar:dist/lib/* peergos.server.tests.CalDavInteropHarness [port]
 * </pre>
 */
public class CalDavInteropHarness {

    public static final String WEBDAV_USER = "peergos";
    public static final String WEBDAV_PASSWORD = "caldav-interop";

    public static void main(String[] args) throws Exception {
        int davPort = args.length > 0 ? Integer.parseInt(args[0]) : 8090;

        Crypto crypto = Main.initCrypto();
        Args peergosArgs = UserTests.buildArgs().with("useIPFS", "false");
        Main.PKI_INIT.main(peergosArgs);
        Path scratch = peergosArgs.fromPeergosDir("", "");
        NetworkAccess network = Builder.buildLocalJavaNetworkAccess(peergosArgs.getInt("port")).get();

        String username = "caldav";
        String password = "caldav-interop-pw";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);
        seed(context);

        Server server = WebdavServer.startNonBlocking(davPort, WEBDAV_USER, WEBDAV_PASSWORD,
                username, password, "http://localhost:" + peergosArgs.getInt("port"), "basic",
                MountConfig.disabled());

        System.out.println();
        System.out.println("=== CalDAV interop harness ===");
        System.out.println("  CalDAV root   http://localhost:" + davPort + "/dav/");
        System.out.println("  calendar home http://localhost:" + davPort + "/dav/calendars/" + username + "/");
        System.out.println("  discovery     http://localhost:" + davPort + "/.well-known/caldav");
        System.out.println("  username      " + WEBDAV_USER);
        System.out.println("  password      " + WEBDAV_PASSWORD);
        System.out.println("  peergos data  " + scratch);
        System.out.println("Seeded calendars: work (3 events, 2 tasks), personal (1 event).");
        System.out.println("Ctrl-C to stop.");
        System.out.println();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                server.stop();
            } catch (Exception e) {
                // going down anyway
            }
            UserTests.deleteFiles(scratch.toFile());
        }));
        server.join();
    }

    /** Written through App, so the layout is exactly what the web calendar app produces. */
    private static void seed(UserContext context) {
        App calendar = App.init(context, "calendar").join();
        write(calendar, "App.config", "{\"calendars\":["
                + "{\"name\":\"Work\",\"directory\":\"work\",\"color\":\"#ff6600\",\"shareable\":true},"
                + "{\"name\":\"Personal\",\"directory\":\"personal\",\"color\":\"#00a9ff\",\"shareable\":true}]}");
        write(calendar, "work/calendar.inf", "{\"name\":\"Work\",\"color\":\"#ff6600\"}");
        write(calendar, "personal/calendar.inf", "{\"name\":\"Personal\",\"color\":\"#00a9ff\"}");

        // Dated relative to today so the events land in whatever month a client opens on.
        LocalDate today = LocalDate.now();
        String shard = "work/" + today.getYear() + "/" + today.getMonthValue() + "/";
        write(calendar, shard + "standup.ics", event("standup", today, "Daily standup"));
        write(calendar, shard + "review.ics", event("review", today.plusDays(2), "Design review"));
        write(calendar, "work/recurring/weekly-sync.ics",
                event("weekly-sync", today.minusDays(7), "Weekly sync")
                        .replace("END:VEVENT", "RRULE:FREQ=WEEKLY\r\nEND:VEVENT"));
        write(calendar, "personal/" + today.getYear() + "/" + today.getMonthValue() + "/dentist.ics",
                event("dentist", today.plusDays(5), "Dentist"));

        // Tasks, for the clients that show a calendar as a task list: one with a due date,
        // one with none at all, which is the case a month-sharded store cannot hold.
        write(calendar, "work/tasks/write-notes.ics", todo("write-notes", today.plusDays(1), "Write up notes"));
        write(calendar, "work/tasks/someday.ics", todo("someday", null, "Someday, maybe"));
    }

    private static String todo(String uid, LocalDate due, String summary) {
        return "BEGIN:VCALENDAR\r\n"
                + "VERSION:2.0\r\n"
                + "PRODID:-//Peergos//CalDAV interop harness//EN\r\n"
                + "BEGIN:VTODO\r\n"
                + "UID:" + uid + "\r\n"
                + "SUMMARY:" + summary + "\r\n"
                + (due == null ? "" : "DUE:" + due.format(DateTimeFormatter.BASIC_ISO_DATE) + "T170000Z\r\n")
                + "END:VTODO\r\n"
                + "END:VCALENDAR\r\n";
    }

    private static String event(String uid, LocalDate day, String summary) {
        String date = day.format(DateTimeFormatter.BASIC_ISO_DATE);
        return "BEGIN:VCALENDAR\r\n"
                + "VERSION:2.0\r\n"
                + "PRODID:-//Peergos//CalDAV interop harness//EN\r\n"
                + "BEGIN:VEVENT\r\n"
                + "UID:" + uid + "\r\n"
                + "SUMMARY:" + summary + "\r\n"
                + "DTSTART:" + date + "T090000Z\r\n"
                + "DTEND:" + date + "T100000Z\r\n"
                + "END:VEVENT\r\n"
                + "END:VCALENDAR\r\n";
    }

    private static void write(App calendar, String relativePath, String content) {
        calendar.writeInternal(PathUtil.get(relativePath), content.getBytes(StandardCharsets.UTF_8), null).join();
    }
}
