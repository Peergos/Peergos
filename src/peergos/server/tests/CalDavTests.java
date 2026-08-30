package peergos.server.tests;

import org.eclipse.jetty.server.Server;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import peergos.server.Builder;
import peergos.server.Main;
import peergos.server.tests.util.TestPorts;
import peergos.server.util.Args;
import peergos.server.webdav.MountConfig;
import peergos.server.webdav.WebdavServer;
import peergos.shared.Crypto;
import peergos.shared.NetworkAccess;
import peergos.shared.user.App;
import peergos.shared.user.UserContext;
import peergos.shared.user.fs.FileWrapper;
import peergos.shared.util.PathUtil;
import peergos.shared.util.Serialize;

import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Optional;
import java.util.Random;

/**
 * Drives the CalDAV bridge over raw HTTP against a RAM Peergos instance, seeding the
 * calendar data through {@link App} so the test writes exactly what the web calendar app
 * writes.
 */
public class CalDavTests {

    private static NetworkAccess network;
    private static Crypto crypto;
    private static Args args;
    private static final Random random = new Random(4791);

    private static final String WEBDAV_USER = "caldavtestuser";
    private static final String WEBDAV_PASSWORD = "caldavtestpass";

    @BeforeClass
    public static void init() throws Exception {
        crypto = Main.initCrypto();
        args = UserTests.buildArgs().with("useIPFS", "false");
        Main.PKI_INIT.main(args);
        network = Builder.buildLocalJavaNetworkAccess(args.getInt("port")).get();
    }

    @AfterClass
    public static void cleanup() {
        UserTests.deleteFiles(args.fromPeergosDir("", "").toFile());
    }

    private static String event(String uid, String start, String end, String extra) {
        return "BEGIN:VCALENDAR\r\n"
                + "VERSION:2.0\r\n"
                + "PRODID:-//Peergos//CalDAV test//EN\r\n"
                + "BEGIN:VEVENT\r\n"
                + "UID:" + uid + "\r\n"
                + "SUMMARY:" + uid + "\r\n"
                + "DTSTART:" + start + "\r\n"
                + "DTEND:" + end + "\r\n"
                + extra
                + "END:VEVENT\r\n"
                + "END:VCALENDAR\r\n";
    }

    private static String todo(String uid, String extra) {
        return "BEGIN:VCALENDAR\r\n"
                + "VERSION:2.0\r\n"
                + "PRODID:-//Peergos//CalDAV test//EN\r\n"
                + "BEGIN:VTODO\r\n"
                + "UID:" + uid + "\r\n"
                + "SUMMARY:" + uid + "\r\n"
                + extra
                + "END:VTODO\r\n"
                + "END:VCALENDAR\r\n";
    }

    private static void write(App calendar, String relativePath, String content) {
        Assert.assertTrue("seeding " + relativePath,
                calendar.writeInternal(PathUtil.get(relativePath), content.getBytes(StandardCharsets.UTF_8), null).join());
    }

    @Test
    public void discoveryListingAndReports() throws Exception {
        String username = "caldav-test" + Math.abs(random.nextInt() % 1_000_000);
        String password = "testpassword";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);

        App calendar = App.init(context, "calendar").join();
        write(calendar, "App.config",
                "{\"calendars\":[{\"name\":\"Work\",\"directory\":\"work\",\"color\":\"#ff0000\"}]}");
        write(calendar, "work/calendar.inf", "{\"name\":\"Work\",\"color\":\"#ff0000\"}");
        write(calendar, "work/2024/3/march-event.ics",
                event("march-event", "20240315T090000Z", "20240315T100000Z", ""));
        write(calendar, "work/2024/7/july-event.ics",
                event("july-event", "20240715T090000Z", "20240715T100000Z", ""));
        write(calendar, "work/recurring/weekly.ics",
                event("weekly", "20240101T090000Z", "20240101T100000Z", "RRULE:FREQ=WEEKLY\r\n"));
        write(calendar, "work/tasks/buy-milk.ics", todo("buy-milk", "DUE:20240320T170000Z\r\n"));

        int port = TestPorts.getPort();
        Server server = WebdavServer.startNonBlocking(port, WEBDAV_USER, WEBDAV_PASSWORD,
                username, password, "http://localhost:" + args.getInt("port"), "basic", MountConfig.disabled());
        try {
            HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
            String base = "http://localhost:" + port;
            String auth = "Basic " + Base64.getEncoder()
                    .encodeToString((WEBDAV_USER + ":" + WEBDAV_PASSWORD).getBytes());

            // OPTIONS must advertise calendar-access, which is how a client decides the
            // server speaks CalDAV at all.
            HttpResponse<String> options = send(client, auth, "OPTIONS", base + "/dav/", null, null);
            Assert.assertEquals(200, options.statusCode());
            Assert.assertTrue("DAV header: " + options.headers().firstValue("DAV"),
                    options.headers().firstValue("DAV").orElse("").contains("calendar-access"));
            // The file tree must keep advertising plain WebDAV.
            HttpResponse<String> fileOptions = send(client, auth, "OPTIONS", base + "/" + username + "/", null, null);
            Assert.assertEquals("1, 2", fileOptions.headers().firstValue("DAV").orElse(""));

            // RFC 6764 discovery from the bare server root.
            HttpResponse<String> wellKnown = send(client, auth, "GET", base + "/.well-known/caldav", null, null);
            Assert.assertEquals(301, wellKnown.statusCode());
            Assert.assertEquals("/dav/", wellKnown.headers().firstValue("Location").orElse(""));

            // Step 1 of client discovery: find the principal.
            HttpResponse<String> principalProbe = propfind(client, auth, base + "/dav/", "0",
                    "<D:propfind xmlns:D=\"DAV:\"><D:prop><D:current-user-principal/></D:prop></D:propfind>");
            Assert.assertEquals(207, principalProbe.statusCode());
            String principalHref = "/dav/principals/" + username + "/";
            Assert.assertTrue("current-user-principal: " + principalProbe.body(),
                    principalProbe.body().contains(principalHref));

            // Step 2: the principal names the calendar home.
            HttpResponse<String> homeProbe = propfind(client, auth, base + principalHref, "0",
                    "<D:propfind xmlns:D=\"DAV:\" xmlns:C=\"urn:ietf:params:xml:ns:caldav\">"
                            + "<D:prop><C:calendar-home-set/></D:prop></D:propfind>");
            Assert.assertEquals(207, homeProbe.statusCode());
            String homeHref = "/dav/calendars/" + username + "/";
            Assert.assertTrue("calendar-home-set: " + homeProbe.body(), homeProbe.body().contains(homeHref));

            // Step 3: the home lists the calendars, with the name and colour the web app stored.
            HttpResponse<String> home = propfind(client, auth, base + homeHref, "1", null);
            Assert.assertEquals(207, home.statusCode());
            Assert.assertTrue("calendar collection: " + home.body(), home.body().contains(homeHref + "work/"));
            Assert.assertTrue("resourcetype: " + home.body(), home.body().contains("<C:calendar/>"));
            Assert.assertTrue("displayname: " + home.body(), home.body().contains("Work"));
            Assert.assertTrue("colour: " + home.body(), home.body().contains("#ff0000"));
            Assert.assertTrue("ctag: " + home.body(), home.body().contains("getctag"));
            // Both components, or a client will never offer the calendar as a task list.
            Assert.assertTrue("VEVENT advertised: " + home.body(), home.body().contains("name=\"VEVENT\""));
            Assert.assertTrue("VTODO advertised: " + home.body(), home.body().contains("name=\"VTODO\""));

            // Step 4: the calendar is flat, so all three events are direct members however
            // they are sharded on disk.
            String calendarHref = homeHref + "work/";
            HttpResponse<String> listing = propfind(client, auth, base + calendarHref, "1",
                    "<D:propfind xmlns:D=\"DAV:\"><D:prop><D:getetag/><D:getcontenttype/></D:prop></D:propfind>");
            Assert.assertEquals(207, listing.statusCode());
            for (String name : new String[]{"march-event.ics", "july-event.ics", "weekly.ics", "buy-milk.ics"})
                Assert.assertTrue(name + " missing from " + listing.body(),
                        listing.body().contains(calendarHref + name));
            Assert.assertTrue("content type: " + listing.body(), listing.body().contains("text/calendar"));
            Assert.assertFalse("calendar.inf must not be served as an event",
                    listing.body().contains("calendar.inf"));
            // Strong ETags: a weak one would collide for two edits in the same second.
            Assert.assertFalse("ETags should be strong: " + listing.body(), listing.body().contains("W/\""));

            // GET one event back.
            HttpResponse<String> get = send(client, auth, "GET", base + calendarHref + "march-event.ics", null, null);
            Assert.assertEquals(200, get.statusCode());
            Assert.assertTrue(get.body().contains("UID:march-event"));
            Assert.assertTrue("ETag on GET", get.headers().firstValue("ETag").isPresent());

            // calendar-query with a time range keeps March and the recurring event, and
            // drops July.
            HttpResponse<String> query = report(client, auth, base + calendarHref,
                    "<C:calendar-query xmlns:D=\"DAV:\" xmlns:C=\"urn:ietf:params:xml:ns:caldav\">"
                            + "<D:prop><D:getetag/><C:calendar-data/></D:prop>"
                            + "<C:filter><C:comp-filter name=\"VCALENDAR\"><C:comp-filter name=\"VEVENT\">"
                            + "<C:time-range start=\"20240301T000000Z\" end=\"20240401T000000Z\"/>"
                            + "</C:comp-filter></C:comp-filter></C:filter></C:calendar-query>");
            Assert.assertEquals(207, query.statusCode());
            Assert.assertTrue("March event in range: " + query.body(), query.body().contains("march-event.ics"));
            Assert.assertTrue("recurring events always match: " + query.body(), query.body().contains("weekly.ics"));
            Assert.assertFalse("July event out of range: " + query.body(), query.body().contains("july-event.ics"));
            Assert.assertTrue("calendar-data returned: " + query.body(), query.body().contains("UID:march-event"));
            Assert.assertFalse("a VEVENT query must not return a task: " + query.body(),
                    query.body().contains("buy-milk.ics"));

            // The task list view of the same collection: a VTODO comp-filter, which is what
            // a tasks client sends, returns the tasks and nothing else.
            HttpResponse<String> tasks = report(client, auth, base + calendarHref,
                    "<C:calendar-query xmlns:D=\"DAV:\" xmlns:C=\"urn:ietf:params:xml:ns:caldav\">"
                            + "<D:prop><D:getetag/><C:calendar-data/></D:prop>"
                            + "<C:filter><C:comp-filter name=\"VCALENDAR\"><C:comp-filter name=\"VTODO\"/>"
                            + "</C:comp-filter></C:filter></C:calendar-query>");
            Assert.assertEquals(207, tasks.statusCode());
            Assert.assertTrue("task in a VTODO query: " + tasks.body(), tasks.body().contains("buy-milk.ics"));
            Assert.assertTrue("task data returned: " + tasks.body(), tasks.body().contains("UID:buy-milk"));
            for (String name : new String[]{"march-event.ics", "july-event.ics", "weekly.ics"})
                Assert.assertFalse("event in a VTODO query: " + tasks.body(), tasks.body().contains(name));

            // calendar-multiget returns exactly the hrefs asked for, and 404s the rest.
            HttpResponse<String> multiget = report(client, auth, base + calendarHref,
                    "<C:calendar-multiget xmlns:D=\"DAV:\" xmlns:C=\"urn:ietf:params:xml:ns:caldav\">"
                            + "<D:prop><D:getetag/><C:calendar-data/></D:prop>"
                            + "<D:href>" + calendarHref + "july-event.ics</D:href>"
                            + "<D:href>" + calendarHref + "missing.ics</D:href>"
                            + "</C:calendar-multiget>");
            Assert.assertEquals(207, multiget.statusCode());
            Assert.assertTrue("requested event: " + multiget.body(), multiget.body().contains("UID:july-event"));
            Assert.assertFalse("unrequested event: " + multiget.body(), multiget.body().contains("UID:march-event"));
            Assert.assertTrue("missing href reported: " + multiget.body(), multiget.body().contains("404"));

            // An initial sync-collection enumerates everything and hands back a token; the
            // same token means nothing changed.
            HttpResponse<String> initialSync = report(client, auth, base + calendarHref,
                    "<D:sync-collection xmlns:D=\"DAV:\"><D:sync-token/><D:sync-level>1</D:sync-level>"
                            + "<D:prop><D:getetag/></D:prop></D:sync-collection>");
            Assert.assertEquals(207, initialSync.statusCode());
            Assert.assertTrue(initialSync.body().contains("march-event.ics"));
            String token = between(initialSync.body(), "<D:sync-token>", "</D:sync-token>");
            Assert.assertFalse("sync token issued: " + initialSync.body(), token.isEmpty());

            HttpResponse<String> repeatSync = report(client, auth, base + calendarHref,
                    "<D:sync-collection xmlns:D=\"DAV:\"><D:sync-token>" + token + "</D:sync-token>"
                            + "<D:sync-level>1</D:sync-level><D:prop><D:getetag/></D:prop></D:sync-collection>");
            Assert.assertEquals(207, repeatSync.statusCode());
            Assert.assertFalse("nothing changed, so no members: " + repeatSync.body(),
                    repeatSync.body().contains("march-event.ics"));

            // A stale token forces a full resync rather than risking a missed deletion.
            HttpResponse<String> staleSync = report(client, auth, base + calendarHref,
                    "<D:sync-collection xmlns:D=\"DAV:\"><D:sync-token>stale</D:sync-token>"
                            + "<D:sync-level>1</D:sync-level><D:prop><D:getetag/></D:prop></D:sync-collection>");
            Assert.assertEquals(403, staleSync.statusCode());
            Assert.assertTrue("valid-sync-token precondition: " + staleSync.body(),
                    staleSync.body().contains("valid-sync-token"));

            // Nothing outside this account's calendars is reachable.
            Assert.assertEquals(404, propfind(client, auth,
                    base + "/dav/calendars/someone-else/", "0", null).statusCode());
            Assert.assertEquals(404, propfind(client, auth,
                    base + calendarHref + "nope.ics", "0", null).statusCode());
        } finally {
            server.stop();
        }
    }

    @Test
    public void writeEventsWhereTheWebAppLooksForThem() throws Exception {
        String username = "caldav-write" + Math.abs(random.nextInt() % 1_000_000);
        String password = "testpassword";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);

        App calendar = App.init(context, "calendar").join();
        write(calendar, "App.config",
                "{\"calendars\":[{\"name\":\"Work\",\"directory\":\"work\",\"color\":\"#ff0000\"}]}");
        write(calendar, "work/calendar.inf", "{\"name\":\"Work\",\"color\":\"#ff0000\"}");
        String configBefore = new String(calendar.readInternal(PathUtil.get("App.config"), null).join(),
                StandardCharsets.UTF_8);
        UserContext verifier = verifier(username, password);

        int port = TestPorts.getPort();
        Server server = WebdavServer.startNonBlocking(port, WEBDAV_USER, WEBDAV_PASSWORD,
                username, password, "http://localhost:" + args.getInt("port"), "basic", MountConfig.disabled());
        try {
            HttpClient client = HttpClient.newHttpClient();
            String base = "http://localhost:" + port;
            String auth = "Basic " + Base64.getEncoder()
                    .encodeToString((WEBDAV_USER + ":" + WEBDAV_PASSWORD).getBytes());
            String work = base + "/dav/calendars/" + username + "/work/";

            // A new event lands in the UTC month directory the web app reads, named the way
            // the web app names it. This is the acceptance test that matters most.
            HttpResponse<String> created = put(client, auth, work + "one.ics",
                    event("one", "20240915T090000Z", "20240915T100000Z", ""), null);
            Assert.assertEquals(201, created.statusCode());
            Assert.assertTrue("PUT should return the new ETag", created.headers().firstValue("ETag").isPresent());
            Assert.assertTrue("event must be under <dir>/<year>/<month>", exists(verifier, username, "work/2024/9/one.ics"));

            // Moving the event across a month boundary moves the file, leaving nothing behind
            // for the flat view to show twice.
            HttpResponse<String> moved = put(client, auth, work + "one.ics",
                    event("one", "20241015T090000Z", "20241015T100000Z", ""), null);
            Assert.assertEquals(204, moved.statusCode());
            Assert.assertTrue(exists(verifier, username, "work/2024/10/one.ics"));
            Assert.assertFalse("old shard must not keep a copy", exists(verifier, username, "work/2024/9/one.ics"));

            // A recurring event goes to recurring/, which the web app loads for every month.
            Assert.assertEquals(201, put(client, auth, work + "repeat.ics",
                    event("repeat", "20240101T090000Z", "20240101T100000Z", "RRULE:FREQ=WEEKLY\r\n"), null).statusCode());
            Assert.assertTrue(exists(verifier, username, "work/recurring/repeat.ics"));

            // Both are single members of the flat collection whatever shard they sit in.
            HttpResponse<String> listing = propfind(client, auth, work, "1", null);
            Assert.assertTrue(listing.body().contains("/work/one.ics"));
            Assert.assertTrue(listing.body().contains("/work/repeat.ics"));

            // A task goes to tasks/, out of the way of the web calendar app, and needs no
            // date at all - which is the whole reason it cannot be filed by month.
            HttpResponse<String> task = put(client, auth, work + "milk.ics", todo("milk", ""), null);
            Assert.assertEquals(201, task.statusCode());
            Assert.assertTrue("task must be under <dir>/tasks", exists(verifier, username, "work/tasks/milk.ics"));
            Assert.assertEquals(200, send(client, auth, "GET", work + "milk.ics", null, null).statusCode());
            // Including a repeating one: recurring/ is the web calendar app's directory.
            Assert.assertEquals(201, put(client, auth, work + "weekly-task.ics",
                    todo("weekly-task", "DUE:20240315T170000Z\r\nRRULE:FREQ=WEEKLY\r\n"), null).statusCode());
            Assert.assertTrue(exists(verifier, username, "work/tasks/weekly-task.ics"));
            Assert.assertFalse(exists(verifier, username, "work/recurring/weekly-task.ics"));
            Assert.assertEquals(204, send(client, auth, "DELETE", work + "milk.ics", null, null).statusCode());
            Assert.assertFalse(exists(verifier, username, "work/tasks/milk.ics"));

            // An event with no start has nowhere the web app would ever read it from.
            HttpResponse<String> undateable = put(client, auth, work + "nodate.ics",
                    "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:nodate\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n", null);
            Assert.assertEquals(403, undateable.statusCode());
            Assert.assertTrue("valid-calendar-data precondition: " + undateable.body(),
                    undateable.body().contains("valid-calendar-data"));
            Assert.assertFalse(exists(verifier, username, "work/recurring/nodate.ics"));

            // Conditional requests: a client must not clobber an edit it has not seen.
            Assert.assertEquals(412, put(client, auth, work + "one.ics",
                    event("one", "20241015T090000Z", "20241015T100000Z", ""), "if-none-match").statusCode());
            Assert.assertEquals(412, put(client, auth, work + "one.ics",
                    event("one", "20241015T090000Z", "20241015T100000Z", ""), "\"not-the-etag\"").statusCode());
            String etag = etagOf(client, auth, work + "one.ics");
            Assert.assertEquals(204, put(client, auth, work + "one.ics",
                    event("one", "20241016T090000Z", "20241016T100000Z", ""), etag).statusCode());

            // DELETE removes the file from its shard.
            Assert.assertEquals(204, send(client, auth, "DELETE", work + "repeat.ics", null, null).statusCode());
            Assert.assertFalse(exists(verifier, username, "work/recurring/repeat.ics"));
            Assert.assertEquals(404, send(client, auth, "GET", work + "repeat.ics", null, null).statusCode());

            // MKCALENDAR creates the directory and calendar.inf, and leaves App.config alone.
            HttpResponse<String> mkcalendar = send(client, auth, "MKCALENDAR",
                    base + "/dav/calendars/" + username + "/personal/",
                    "<C:mkcalendar xmlns:D=\"DAV:\" xmlns:C=\"urn:ietf:params:xml:ns:caldav\" "
                            + "xmlns:A=\"http://apple.com/ns/ical/\"><D:set><D:prop>"
                            + "<D:displayname>Personal</D:displayname><A:calendar-color>#00ff00</A:calendar-color>"
                            + "</D:prop></D:set></C:mkcalendar>", null);
            Assert.assertEquals(201, mkcalendar.statusCode());
            Assert.assertEquals("{\"name\":\"Personal\",\"color\":\"#00ff00\"}", read(verifier, username, "personal/calendar.inf"));
            Assert.assertEquals("App.config must not be rewritten by the bridge", configBefore,
                    read(verifier, username, "App.config"));
            Assert.assertEquals("MKCALENDAR over an existing calendar", 405, send(client, auth, "MKCALENDAR",
                    base + "/dav/calendars/" + username + "/personal/", null, null).statusCode());

            // The new calendar is usable straight away.
            Assert.assertEquals(201, put(client, auth,
                    base + "/dav/calendars/" + username + "/personal/two.ics",
                    event("two", "20240915T090000Z", "20240915T100000Z", ""), null).statusCode());
            Assert.assertTrue(exists(verifier, username, "personal/2024/9/two.ics"));

            // A calendar the bridge created is its to remove; one the web app lists is not,
            // because deleting it would strand an App.config entry we will not rewrite.
            Assert.assertEquals(403, send(client, auth, "DELETE", work, null, null).statusCode());
            Assert.assertTrue(exists(verifier, username, "work/calendar.inf"));
            Assert.assertEquals(204, send(client, auth, "DELETE",
                    base + "/dav/calendars/" + username + "/personal/", null, null).statusCode());
            Assert.assertFalse(exists(verifier, username, "personal/calendar.inf"));
        } finally {
            server.stop();
        }
    }

    /**
     * A session for reading back what the server wrote. It cannot be the one that seeded the
     * data: {@code buildJavaNetworkAccess} caches each writer's pointer for 7 seconds, so
     * that session would not see another session's writes until the cache expired.
     */
    private static UserContext verifier(String username, String password) throws Exception {
        NetworkAccess uncached = Builder.buildNonCachingJavaNetworkAccess(
                new URL("http://localhost:" + args.getInt("port") + "/"), false, 0,
                Optional.empty(), Optional.empty(), Optional.empty()).join();
        return PeergosNetworkUtils.ensureSignedUp(username, password, uncached, crypto);
    }

    private static boolean exists(UserContext verifier, String username, String relative) {
        return verifier.getByPath(username + "/.apps/calendar/data/" + relative).join().isPresent();
    }

    private static String read(UserContext verifier, String username, String relative) {
        FileWrapper file = verifier.getByPath(username + "/.apps/calendar/data/" + relative).join().get();
        return new String(Serialize.readFully(file
                .getInputStream(verifier.network, verifier.crypto, file.getSize(), l -> {}).join(),
                file.getSize()).join(), StandardCharsets.UTF_8);
    }

    private static String etagOf(HttpClient client, String auth, String url) throws Exception {
        return send(client, auth, "HEAD", url, null, null).headers().firstValue("ETag").orElse("");
    }

    private static HttpResponse<String> put(HttpClient client, String auth, String url,
                                            String ics, String ifHeader) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .PUT(HttpRequest.BodyPublishers.ofString(ics))
                .header("Authorization", auth)
                .header("Content-Type", "text/calendar; charset=utf-8");
        if ("if-none-match".equals(ifHeader))
            request = request.header("If-None-Match", "*");
        else if (ifHeader != null)
            request = request.header("If-Match", ifHeader);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    public void syncCollectionReportsOnlyWhatChanged() throws Exception {
        String username = "caldav-sync" + Math.abs(random.nextInt() % 1_000_000);
        String password = "testpassword";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);

        App calendar = App.init(context, "calendar").join();
        write(calendar, "App.config",
                "{\"calendars\":[{\"name\":\"Work\",\"directory\":\"work\",\"color\":\"#ff0000\"}]}");
        write(calendar, "work/calendar.inf", "{\"name\":\"Work\",\"color\":\"#ff0000\"}");
        write(calendar, "work/2024/3/first.ics", event("first", "20240315T090000Z", "20240315T100000Z", ""));
        write(calendar, "work/2024/3/second.ics", event("second", "20240316T090000Z", "20240316T100000Z", ""));

        int port = TestPorts.getPort();
        Server server = WebdavServer.startNonBlocking(port, WEBDAV_USER, WEBDAV_PASSWORD,
                username, password, "http://localhost:" + args.getInt("port"), "basic", MountConfig.disabled());
        try {
            HttpClient client = HttpClient.newHttpClient();
            String base = "http://localhost:" + port;
            String auth = "Basic " + Base64.getEncoder()
                    .encodeToString((WEBDAV_USER + ":" + WEBDAV_PASSWORD).getBytes());
            String work = base + "/dav/calendars/" + username + "/work/";

            // Initial sync enumerates everything and issues a token.
            HttpResponse<String> initial = report(client, auth, work, syncRequest(""));
            Assert.assertEquals(207, initial.statusCode());
            Assert.assertTrue(initial.body().contains("first.ics"));
            Assert.assertTrue(initial.body().contains("second.ics"));
            String token = between(initial.body(), "<D:sync-token>", "</D:sync-token>");
            Assert.assertFalse(token.isEmpty());

            // Nothing has changed, so the same token comes back with no members.
            HttpResponse<String> unchanged = report(client, auth, work, syncRequest(token));
            Assert.assertEquals(207, unchanged.statusCode());
            Assert.assertFalse("no members when nothing changed: " + unchanged.body(),
                    unchanged.body().contains(".ics"));
            Assert.assertEquals("an unchanged collection keeps its token", token,
                    between(unchanged.body(), "<D:sync-token>", "</D:sync-token>"));

            // Add one and modify another; the untouched one must not be reported.
            Assert.assertEquals(201, put(client, auth, work + "third.ics",
                    event("third", "20240317T090000Z", "20240317T100000Z", ""), null).statusCode());
            Assert.assertEquals(204, put(client, auth, work + "first.ics",
                    event("first", "20240315T110000Z", "20240315T120000Z", ""), null).statusCode());

            HttpResponse<String> incremental = report(client, auth, work, syncRequest(token));
            Assert.assertEquals(207, incremental.statusCode());
            Assert.assertTrue("new member: " + incremental.body(), incremental.body().contains("third.ics"));
            Assert.assertTrue("changed member: " + incremental.body(), incremental.body().contains("first.ics"));
            Assert.assertFalse("untouched member must not be resent: " + incremental.body(),
                    incremental.body().contains("second.ics"));
            String afterAdds = between(incremental.body(), "<D:sync-token>", "</D:sync-token>");
            Assert.assertNotEquals(token, afterAdds);

            // A deletion is reported as a 404 response, which is the only way the client
            // learns to drop it. This is what the forced-resync answer could never express.
            Assert.assertEquals(204, send(client, auth, "DELETE", work + "second.ics", null, null).statusCode());
            HttpResponse<String> afterDelete = report(client, auth, work, syncRequest(afterAdds));
            Assert.assertEquals(207, afterDelete.statusCode());
            Assert.assertTrue("deleted member reported: " + afterDelete.body(),
                    afterDelete.body().contains("second.ics"));
            Assert.assertTrue("deleted member marked 404: " + afterDelete.body(),
                    afterDelete.body().contains("404"));
            Assert.assertFalse("surviving members must not be resent: " + afterDelete.body(),
                    afterDelete.body().contains("third.ics"));

            // A token we never issued still forces a full resync.
            HttpResponse<String> bogus = report(client, auth, work, syncRequest("urn:x-made-up"));
            Assert.assertEquals(403, bogus.statusCode());
            Assert.assertTrue(bogus.body().contains("valid-sync-token"));

            // The ctag tracks the collection, not the account: an unrelated write elsewhere
            // in the user's space must leave it alone.
            String ctagBefore = ctag(client, auth, work);
            write(calendar, "unrelated/calendar.inf", "{\"name\":\"Unrelated\"}");
            Assert.assertEquals("ctag must not move for a change outside the collection",
                    ctagBefore, ctag(client, auth, work));
        } finally {
            server.stop();
        }
    }

    private static String syncRequest(String token) {
        return "<D:sync-collection xmlns:D=\"DAV:\"><D:sync-token>" + token + "</D:sync-token>"
                + "<D:sync-level>1</D:sync-level><D:prop><D:getetag/></D:prop></D:sync-collection>";
    }

    private static String ctag(HttpClient client, String auth, String url) throws Exception {
        String body = propfind(client, auth, url, "0",
                "<D:propfind xmlns:D=\"DAV:\" xmlns:CS=\"http://calendarserver.org/ns/\">"
                        + "<D:prop><CS:getctag/></D:prop></D:propfind>").body();
        return between(body, "<CS:getctag>", "</CS:getctag>");
    }

    private static String between(String body, String open, String close) {
        int from = body.indexOf(open);
        if (from < 0)
            return "";
        int to = body.indexOf(close, from);
        return to < 0 ? "" : body.substring(from + open.length(), to);
    }

    private static HttpResponse<String> propfind(HttpClient client, String auth, String url,
                                                 String depth, String body) throws Exception {
        return send(client, auth, "PROPFIND", url, body, depth);
    }

    private static HttpResponse<String> report(HttpClient client, String auth, String url,
                                               String body) throws Exception {
        return send(client, auth, "REPORT", url, body, "1");
    }

    private static HttpResponse<String> send(HttpClient client, String auth, String method,
                                             String url, String body, String depth) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body))
                .header("Authorization", auth);
        if (body != null)
            request = request.header("Content-Type", "application/xml; charset=utf-8");
        if (depth != null)
            request = request.header("Depth", depth);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
