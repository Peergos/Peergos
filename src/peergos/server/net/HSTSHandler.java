package peergos.server.net;

import com.sun.net.httpserver.*;

import java.util.*;

public class HSTSHandler extends ResponseHeaderHandler {

    static Map<String, String> getHeaders() {
        Map<String, String> res = new HashMap<>();
        // use https only for at least 1 year
        res.put("Strict-Transport-Security", "max-age=31536000");
        return res;
    }

    static final Map<String, String> HSTS = getHeaders();

    public HSTSHandler(HttpHandler handler) {
        super(HSTS, handler);
    }
}
