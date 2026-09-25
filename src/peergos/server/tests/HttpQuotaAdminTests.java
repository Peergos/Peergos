package peergos.server.tests;

import com.sun.net.httpserver.*;
import org.junit.*;
import peergos.server.storage.admin.*;
import peergos.server.util.*;
import peergos.shared.cbor.*;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.*;

/** Listing and withdrawing signup tokens on a paid instance, where the tokens live in the quota service. */
public class HttpQuotaAdminTests {

    @Test
    public void listsAndDeletesTokensInTheQuotaService() throws Exception {
        Set<String> tokens = new HashSet<>(List.of("aaaa", "bb&username=someone"));
        HttpServer service = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        service.createContext("/" + HttpQuotaAdmin.QUOTA_URL, ex -> {
            String call = ex.getRequestURI().getPath().substring(("/" + HttpQuotaAdmin.QUOTA_URL).length());
            Map<String, List<String>> params = HttpUtil.parseQuery(ex.getRequestURI().getRawQuery());
            Cborable reply;
            if (call.equals(HttpQuotaAdmin.TOKEN_LIST))
                reply = new CborObject.CborList(tokens.stream().map(CborObject.CborString::new).collect(Collectors.toList()));
            else if (call.equals(HttpQuotaAdmin.TOKEN_DELETE) && params.get("token").size() == 1 && ! params.containsKey("username"))
                reply = new CborObject.CborBoolean(tokens.remove(URLDecoder.decode(params.get("token").get(0), StandardCharsets.UTF_8)));
            else
                reply = new CborObject.CborBoolean(false);
            byte[] body = reply.serialize();
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        service.start();
        try {
            HttpQuotaAdmin quotas = new HttpQuotaAdmin(new JavaPoster(new URI("http://127.0.0.1:" + service.getAddress().getPort()).toURL(), false));
            Assert.assertEquals(Set.of("aaaa", "bb&username=someone"), new HashSet<>(quotas.listTokens()));

            // an admin supplied token arrives as one value, not as extra parameters
            Assert.assertTrue(quotas.removeToken("bb&username=someone"));
            Assert.assertFalse("already gone", quotas.removeToken("bb&username=someone"));
            Assert.assertEquals(List.of("aaaa"), quotas.listTokens());
        } finally {
            service.stop(0);
        }
    }
}
