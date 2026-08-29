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
import peergos.shared.user.UserContext;

import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.Optional;
import java.util.Random;

/**
 * CardDAV over the bridge. Unlike the calendar there is no web app writing this data yet,
 * so these tests are also the specification of the layout: contacts land in
 * {@code <user>/.apps/contacts/data/<book>/<uid>.vcf}.
 */
public class CardDavTests {

    private static NetworkAccess network;
    private static Crypto crypto;
    private static Args args;
    private static final Random random = new Random(6352);

    private static final String WEBDAV_USER = "carddavtestuser";
    private static final String WEBDAV_PASSWORD = "carddavtestpass";

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

    private static String card(String uid, String name, String email) {
        return "BEGIN:VCARD\r\n"
                + "VERSION:3.0\r\n"
                + "UID:" + uid + "\r\n"
                + "FN:" + name + "\r\n"
                + "N:" + name + ";;;;\r\n"
                + "EMAIL;TYPE=INTERNET:" + email + "\r\n"
                + "END:VCARD\r\n";
    }

    @Test
    public void addressBookDiscoveryReadWriteAndSync() throws Exception {
        String username = "carddav-test" + Math.abs(random.nextInt() % 1_000_000);
        String password = "testpassword";
        PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);
        UserContext verifier = verifier(username, password);

        int port = TestPorts.getPort();
        Server server = WebdavServer.startNonBlocking(port, WEBDAV_USER, WEBDAV_PASSWORD,
                username, password, "http://localhost:" + args.getInt("port"), "basic", MountConfig.disabled());
        try {
            HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
            String base = "http://localhost:" + port;
            String auth = "Basic " + Base64.getEncoder()
                    .encodeToString((WEBDAV_USER + ":" + WEBDAV_PASSWORD).getBytes());
            String book = base + "/dav/addressbooks/" + username + "/default/";

            // OPTIONS must advertise addressbook alongside calendar-access.
            Assert.assertTrue("DAV header", send(client, auth, "OPTIONS", base + "/dav/", null, null)
                    .headers().firstValue("DAV").orElse("").contains("addressbook"));
            HttpResponse<String> wellKnown = send(client, auth, "GET", base + "/.well-known/carddav", null, null);
            Assert.assertEquals(301, wellKnown.statusCode());

            // The principal names the address book home, next to the calendar one.
            HttpResponse<String> principal = propfind(client, auth,
                    base + "/dav/principals/" + username + "/", "0",
                    "<D:propfind xmlns:D=\"DAV:\" xmlns:CARD=\"urn:ietf:params:xml:ns:carddav\">"
                            + "<D:prop><CARD:addressbook-home-set/></D:prop></D:propfind>");
            Assert.assertEquals(207, principal.statusCode());
            Assert.assertTrue("addressbook-home-set: " + principal.body(),
                    principal.body().contains("/dav/addressbooks/" + username + "/"));

            // The home lists the default book, typed as an address book not a calendar.
            HttpResponse<String> home = propfind(client, auth,
                    base + "/dav/addressbooks/" + username + "/", "1", null);
            Assert.assertEquals(207, home.statusCode());
            Assert.assertTrue("resourcetype: " + home.body(), home.body().contains("<CARD:addressbook/>"));
            Assert.assertFalse("must not claim to be a calendar", home.body().contains("<C:calendar/>"));
            Assert.assertTrue("supported-address-data: " + home.body(),
                    home.body().contains("supported-address-data"));

            // Writing a contact puts it where a future contacts app would look for it.
            HttpResponse<String> created = put(client, auth, book + "alice.vcf",
                    card("alice", "Alice Example", "alice@example.com"));
            Assert.assertEquals(201, created.statusCode());
            Assert.assertTrue("PUT returns an ETag", created.headers().firstValue("ETag").isPresent());
            Assert.assertTrue("stored flat under the book",
                    verifier.getByPath(username + "/.apps/contacts/data/default/alice.vcf").join().isPresent());

            Assert.assertEquals(201, put(client, auth, book + "bob.vcf",
                    card("bob", "Bob Sample", "bob@example.org")).statusCode());

            // GET returns the vCard with the right content type.
            HttpResponse<String> fetched = send(client, auth, "GET", book + "alice.vcf", null, null);
            Assert.assertEquals(200, fetched.statusCode());
            Assert.assertTrue(fetched.body().contains("FN:Alice Example"));
            Assert.assertTrue("content type: " + fetched.headers().firstValue("Content-Type"),
                    fetched.headers().firstValue("Content-Type").orElse("").startsWith("text/vcard"));

            // addressbook-multiget returns the requested hrefs with their address data.
            HttpResponse<String> multiget = report(client, auth, book,
                    "<CARD:addressbook-multiget xmlns:D=\"DAV:\" xmlns:CARD=\"urn:ietf:params:xml:ns:carddav\">"
                            + "<D:prop><D:getetag/><CARD:address-data/></D:prop>"
                            + "<D:href>" + "/dav/addressbooks/" + username + "/default/bob.vcf</D:href>"
                            + "</CARD:addressbook-multiget>");
            Assert.assertEquals(207, multiget.statusCode());
            Assert.assertTrue(multiget.body().contains("FN:Bob Sample"));
            Assert.assertFalse(multiget.body().contains("FN:Alice Example"));

            // addressbook-query filters on a property, case-insensitively.
            HttpResponse<String> query = report(client, auth, book,
                    "<CARD:addressbook-query xmlns:D=\"DAV:\" xmlns:CARD=\"urn:ietf:params:xml:ns:carddav\">"
                            + "<D:prop><D:getetag/><CARD:address-data/></D:prop>"
                            + "<CARD:filter><CARD:prop-filter name=\"FN\">"
                            + "<CARD:text-match>alice</CARD:text-match>"
                            + "</CARD:prop-filter></CARD:filter></CARD:addressbook-query>");
            Assert.assertEquals(207, query.statusCode());
            Assert.assertTrue("matching contact: " + query.body(), query.body().contains("alice.vcf"));
            Assert.assertFalse("non-matching contact: " + query.body(), query.body().contains("bob.vcf"));

            // sync-collection works the same as for calendars, deletions included.
            HttpResponse<String> initial = report(client, auth, book, syncRequest(""));
            String token = between(initial.body(), "<D:sync-token>", "</D:sync-token>");
            Assert.assertFalse(token.isEmpty());
            Assert.assertEquals(204, send(client, auth, "DELETE", book + "bob.vcf", null, null).statusCode());
            HttpResponse<String> afterDelete = report(client, auth, book, syncRequest(token));
            Assert.assertEquals(207, afterDelete.statusCode());
            Assert.assertTrue("deleted contact reported: " + afterDelete.body(),
                    afterDelete.body().contains("bob.vcf"));
            Assert.assertTrue(afterDelete.body().contains("404"));
            Assert.assertFalse("surviving contact not resent", afterDelete.body().contains("alice.vcf"));

            // MKCOL creates a second address book, and it is usable at once.
            HttpResponse<String> mkcol = send(client, auth, "MKCOL",
                    base + "/dav/addressbooks/" + username + "/work/",
                    "<D:mkcol xmlns:D=\"DAV:\" xmlns:CARD=\"urn:ietf:params:xml:ns:carddav\"><D:set><D:prop>"
                            + "<D:resourcetype><D:collection/><CARD:addressbook/></D:resourcetype>"
                            + "<D:displayname>Work</D:displayname></D:prop></D:set></D:mkcol>", null);
            Assert.assertEquals(201, mkcol.statusCode());
            Assert.assertEquals("{\"name\":\"Work\"}",
                    read(verifier, username, "work/addressbook.inf"));
            Assert.assertEquals(201, put(client, auth,
                    base + "/dav/addressbooks/" + username + "/work/carol.vcf",
                    card("carol", "Carol Test", "carol@example.net")).statusCode());

            // Something that is not a vCard has nothing to identify it by.
            HttpResponse<String> rubbish = put(client, auth, book + "junk.vcf", "not a vcard at all");
            Assert.assertEquals(403, rubbish.statusCode());
            Assert.assertTrue("valid-address-data: " + rubbish.body(),
                    rubbish.body().contains("valid-address-data"));

            // Calendars and address books stay in separate trees.
            Assert.assertEquals(404, propfind(client, auth,
                    base + "/dav/calendars/" + username + "/default/alice.vcf", "0", null).statusCode());
        } finally {
            server.stop();
        }
    }

    private static UserContext verifier(String username, String password) throws Exception {
        NetworkAccess uncached = Builder.buildNonCachingJavaNetworkAccess(
                new URL("http://localhost:" + args.getInt("port") + "/"), false, 0,
                Optional.empty(), Optional.empty(), Optional.empty()).join();
        return PeergosNetworkUtils.ensureSignedUp(username, password, uncached, crypto);
    }

    private static String read(UserContext verifier, String username, String relative) {
        var file = verifier.getByPath(username + "/.apps/contacts/data/" + relative).join().get();
        return new String(peergos.shared.util.Serialize.readFully(file
                .getInputStream(verifier.network, verifier.crypto, file.getSize(), l -> {}).join(),
                file.getSize()).join(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String syncRequest(String token) {
        return "<D:sync-collection xmlns:D=\"DAV:\"><D:sync-token>" + token + "</D:sync-token>"
                + "<D:sync-level>1</D:sync-level><D:prop><D:getetag/></D:prop></D:sync-collection>";
    }

    private static String between(String body, String open, String close) {
        int from = body.indexOf(open);
        if (from < 0)
            return "";
        int to = body.indexOf(close, from);
        return to < 0 ? "" : body.substring(from + open.length(), to);
    }

    private static HttpResponse<String> put(HttpClient client, String auth, String url, String vcard)
            throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url))
                .PUT(HttpRequest.BodyPublishers.ofString(vcard))
                .header("Authorization", auth)
                .header("Content-Type", "text/vcard; charset=utf-8").build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> propfind(HttpClient client, String auth, String url,
                                                 String depth, String body) throws Exception {
        return send(client, auth, "PROPFIND", url, body, depth);
    }

    private static HttpResponse<String> report(HttpClient client, String auth, String url, String body)
            throws Exception {
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
