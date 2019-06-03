package peergos.server.storage.admin;

import com.sun.net.httpserver.*;
import peergos.server.util.*;
import peergos.shared.io.ipfs.api.*;
import peergos.shared.storage.controller.*;
import peergos.shared.util.*;

import java.io.*;
import java.net.*;

public class AdminHandler implements HttpHandler {

    private final InstanceAdmin target;

    public AdminHandler(InstanceAdmin target) {
        this.target = target;
    }

    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.startsWith("/"))
            path = path.substring(1);
        String[] subComponents = path.substring(Constants.ADMIN_URL.length()).split("/");
        String method = subComponents[0];

        String reply;
        try {
            switch (method) {
                case InstanceAdmin.HTTP.VERSION:
                    InstanceAdmin.VersionInfo res = target.getVersionInfo().join();
                    reply = JSONParser.toString(res.toJSON());
                    break;
                default:
                    throw new IOException("Unknown method "+ method);
            }

            byte[] b = reply.getBytes();
            exchange.sendResponseHeaders(200, b.length);
            exchange.getResponseBody().write(b);
        } catch (Exception e) {
            HttpUtil.replyError(exchange, e);
        } finally {
            exchange.close();
        }

    }
}
