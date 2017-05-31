package peergos.server.net;

import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.io.ipfs.api.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.storage.ContentAddressedStorage;
import com.sun.net.httpserver.*;
import peergos.shared.util.*;

import java.io.*;
import java.util.*;
import java.util.function.*;
import java.util.stream.*;

public class DHTHandler implements HttpHandler
{
    private static final boolean LOGGING = true;
    private final ContentAddressedStorage dht;
    private final String apiPrefix;

    public DHTHandler(ContentAddressedStorage dht, String apiPrefix) throws IOException
    {
        this.dht = dht;
        this.apiPrefix = apiPrefix;
    }

    public DHTHandler(ContentAddressedStorage dht) throws IOException {
        this(dht, "/api/v0/");
    }

    private Map<String, List<String>> parseQuery(String query) {
        if (query == null)
            return Collections.emptyMap();
        if (query.startsWith("?"))
            query = query.substring(1);
        String[] parts = query.split("&");
        Map<String, List<String>> res = new HashMap<>();
        for (String part : parts) {
            int sep = part.indexOf("=");
            String key = part.substring(0, sep);
            String value = part.substring(sep + 1);
            res.putIfAbsent(key, new ArrayList<>());
            res.get(key).add(value);
        }
        return res;
    }

    @Override
    public void handle(HttpExchange httpExchange) throws IOException {
        long t1 = System.currentTimeMillis();
        String path = httpExchange.getRequestURI().getPath();
        try {
            if (! path.startsWith(apiPrefix))
                throw new IllegalStateException("Unsupported api version, required: " + apiPrefix);
            path = path.substring(apiPrefix.length());
            // N.B. URI.getQuery() decodes the query string
            Map<String, List<String>> params = parseQuery(httpExchange.getRequestURI().getQuery());
            List<String> args = params.get("arg");
            Function<String, String> last = key -> params.get(key).get(params.get(key).size() - 1);

            switch (path) {
                case "block/put": {
                    PublicSigningKey writer = PublicSigningKey.fromString(last.apply("writer"));
                    String boundary = httpExchange.getRequestHeaders().get("Content-Type")
                            .stream()
                            .filter(s -> s.contains("boundary="))
                            .map(s -> s.substring(s.indexOf("=") + 1))
                            .findAny()
                            .get();
                    List<byte[]> data = MultipartReceiver.extractFiles(httpExchange.getRequestBody(), boundary);
                    boolean isRaw = last.apply("format").equals("raw");

                    (isRaw ? dht.putRaw(writer, data) : dht.put(writer, data)).thenAccept(hashes -> {
                        List<Object> json = hashes.stream().map(h -> wrapHash(h)).collect(Collectors.toList());
                        // make stream of JSON objects
                        String jsonStream = json.stream().map(m -> JSONParser.toString(m)).reduce("", (a, b) -> a + b);
                        replyJson(httpExchange, jsonStream, Optional.empty());
                    }).exceptionally(Futures::logError);
                    break;
                }
                case "block/get":{
                    Multihash hash = Cid.decode(args.get(0));
                    dht.get(hash)
                            .thenAccept(opt -> replyBytes(httpExchange,
                                    opt.map(CborObject::toByteArray).orElse(new byte[0]), opt.map(x -> hash)))
                            .exceptionally(Futures::logError);
                    break;
                }
                case "pin/add": {
                    Multihash hash = Cid.decode(args.get(0));
                    dht.recursivePin(hash).thenAccept(pinned -> {
                        Map<String, Object> json = new TreeMap<>();
                        json.put("Pins", pinned.stream().map(h -> h.toString()).collect(Collectors.toList()));
                        replyJson(httpExchange, JSONParser.toString(json), Optional.empty());
                    }).exceptionally(Futures::logError);
                    break;
                }
                case "pin/rm": {
                    boolean recursive = params.containsKey("r") && Boolean.parseBoolean(last.apply("r"));
                    if (!recursive)
                        throw new IllegalStateException("Unimplemented: non recursive unpin!");
                    Multihash hash = Cid.decode(args.get(0));
                    dht.recursiveUnpin(hash).thenAccept(unpinned -> {
                        Map<String, Object> json = new TreeMap<>();
                        json.put("Pins", unpinned.stream().map(h -> h.toString()).collect(Collectors.toList()));
                        replyJson(httpExchange, JSONParser.toString(json), Optional.empty());
                    }).exceptionally(Futures::logError);
                    break;
                }
                case "block/stat": {
                    Multihash block = Cid.decode(args.get(0));
                    dht.getSize(block).thenAccept(sizeOpt -> {
                        Map<String, Object> res = new HashMap<>();
                        res.put("Size", sizeOpt.orElse(0));
                        String json = JSONParser.toString(res);
                        replyJson(httpExchange, json, Optional.of(block));
                    }).exceptionally(Futures::logError);
                    break;
                }
                case "refs": {
                    Multihash block = Cid.decode(args.get(0));
                    dht.getLinks(block).thenAccept(links -> {
                        List<Object> json = links.stream().map(h -> wrapHash("Ref", h)).collect(Collectors.toList());
                        // make stream of JSON objects
                        String jsonStream = json.stream().map(m -> JSONParser.toString(m)).reduce("", (a, b) -> a + b);
                        replyJson(httpExchange, jsonStream, Optional.of(block));
                    }).exceptionally(Futures::logError);
                    break;
                }
                default: {
                    httpExchange.sendResponseHeaders(404, 0);
                }
            }
        } catch (Exception e) {
            System.err.println("Error handling " +httpExchange.getRequestURI());
            e.printStackTrace();
            replyError(httpExchange, e);
        } finally {
            long t2 = System.currentTimeMillis();
            if (LOGGING)
                System.out.println("DHT Handler handled " + path + " query in: " + (t2 - t1) + " mS");
        }
    }

    private static Map<String, Object> wrapHash(Multihash h) {
        return wrapHash("Hash", h);
    }

    private static Map<String, Object> wrapHash(String key, Multihash h) {
        Map<String, Object> json = new TreeMap<>();
        json.put(key, h.toString());
        return json;
    }

    private static void replyError(HttpExchange exchange, Throwable t) {
        try {
            exchange.sendResponseHeaders(500, 0);
            DataOutputStream dout = new DataOutputStream(exchange.getResponseBody());
            String body = t.getMessage();
            dout.write(body.getBytes());
            dout.flush();
            dout.close();
        } catch (IOException e)
        {
            e.printStackTrace();
        }
    }

    private static void replyJson(HttpExchange exchange, String json, Optional<Multihash> key) {
        try {
            if (key.isPresent()) {
                exchange.getResponseHeaders().set("Cache-Control", "public, max-age=31622400 immutable");
                exchange.getResponseHeaders().set("ETag", "\"" + key.get().toString() + "\"");
            }
            exchange.sendResponseHeaders(200, 0);
            DataOutputStream dout = new DataOutputStream(exchange.getResponseBody());
            dout.write(json.getBytes());
            dout.flush();
            dout.close();
        } catch (IOException e)
        {
            e.printStackTrace();
        }
    }

    private static void replyBytes(HttpExchange exchange, byte[] body, Optional<Multihash> key) {
        try {
            if (key.isPresent()) {
                exchange.getResponseHeaders().set("Cache-Control", "public, max-age=31622400 immutable");
                exchange.getResponseHeaders().set("ETag", "\"" + key.get().toString() + "\"");
            }
            exchange.sendResponseHeaders(200, 0);
            DataOutputStream dout = new DataOutputStream(exchange.getResponseBody());
            dout.write(body);
            dout.flush();
            dout.close();
        } catch (IOException e)
        {
            e.printStackTrace();
        }
    }
}
