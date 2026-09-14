package peergos.server.tests;

import org.junit.Assert;
import org.junit.Test;
import peergos.server.Main;
import peergos.server.util.HttpUtil;
import peergos.shared.Crypto;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.storage.ContentAddressedStorage;
import peergos.shared.storage.ContentAddressedStorageProxy;
import peergos.shared.storage.auth.Bat;
import peergos.shared.storage.auth.BatWithId;
import peergos.shared.user.HttpPoster;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** The mirror asks for link counts on every run, so a query string the server can't parse takes
 *  secret link count synchronisation down silently.
 */
public class LinkCountsUrlTests {

    private static final Crypto crypto = Main.initCrypto();

    /** Captures the url a call builds, and fails the call so nothing tries to parse a response. */
    private static class UrlCapture implements HttpPoster {
        String url;

        @Override
        public CompletableFuture<byte[]> postUnzip(String url, byte[] payload, int timeoutMillis) {
            this.url = url;
            return CompletableFuture.failedFuture(new IllegalStateException("not sent"));
        }

        @Override
        public CompletableFuture<byte[]> get(String url, Map<String, String> headers) {
            this.url = url;
            return CompletableFuture.failedFuture(new IllegalStateException("not sent"));
        }

        @Override
        public CompletableFuture<byte[]> post(String url, byte[] payload, boolean unzip, int timeoutMillis) {
            throw new IllegalStateException("unused");
        }

        @Override
        public CompletableFuture<byte[]> postMultipart(String url, List<byte[]> files, int timeoutMillis) {
            throw new IllegalStateException("unused");
        }

        @Override
        public CompletableFuture<byte[]> put(String url, byte[] payload, Map<String, String> headers) {
            throw new IllegalStateException("unused");
        }
    }

    private static BatWithId mirrorBat() {
        Cid id = Cid.buildCidV1(Cid.Codec.Raw, Multihash.Type.sha2_256, crypto.random.randomBytes(32));
        return new BatWithId(Bat.random(crypto.random), id);
    }

    /** The server reads owner, after and bat as three separate parameters. */
    private static void assertParsesIntoThreeParams(String url, BatWithId bat, LocalDateTime after, String owner) {
        int q = url.indexOf('?');
        Assert.assertTrue("there is a query string", q >= 0);
        Assert.assertEquals("only one ? in " + url, q, url.lastIndexOf('?'));

        Map<String, List<String>> params = HttpUtil.parseQuery(url.substring(q));
        Assert.assertEquals(Set.of("after", "bat", "owner"), params.keySet());
        Assert.assertEquals(after.toEpochSecond(ZoneOffset.UTC),
                Long.parseLong(params.get("after").get(0)));
        Assert.assertEquals(bat.encode(), params.get("bat").get(0));
        Assert.assertEquals(owner, params.get("owner").get(0));
    }

    @Test
    public void directLinkCountsUrlIsParseable() {
        UrlCapture poster = new UrlCapture();
        BatWithId bat = mirrorBat();
        LocalDateTime after = LocalDateTime.of(2026, 9, 1, 12, 0);

        new ContentAddressedStorage.HTTP(poster, true, crypto.hasher).getLinkCounts("alice", after, bat);

        assertParsesIntoThreeParams(poster.url, bat, after, "alice");
    }

    @Test
    public void proxiedLinkCountsUrlIsParseable() {
        UrlCapture poster = new UrlCapture();
        BatWithId bat = mirrorBat();
        LocalDateTime after = LocalDateTime.of(2026, 9, 1, 12, 0);
        Multihash target = Cid.buildCidV1(Cid.Codec.LibP2pKey, Multihash.Type.id, crypto.random.randomBytes(36));

        new ContentAddressedStorageProxy.HTTP(poster).getLinkCounts(target, "alice", after, bat);

        assertParsesIntoThreeParams(poster.url, bat, after, "alice");
    }
}
