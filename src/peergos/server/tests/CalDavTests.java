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
import peergos.shared.util.PathUtil;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
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

            // Step 4: the calendar is flat, so all three events are direct members however
            // they are sharded on disk.
            String calendarHref = homeHref + "work/";
            HttpResponse<String> listing = propfind(client, auth, base + calendarHref, "1",
                    "<D:propfind xmlns:D=\"DAV:\"><D:prop><D:getetag/><D:getcontenttype/></D:prop></D:propfind>");
            Assert.assertEquals(207, listing.statusCode());
            for (String name : new String[]{"march-event.ics", "july-event.ics", "weekly.ics"})
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
