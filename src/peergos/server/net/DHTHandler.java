package peergos.server.net;

import peergos.shared.crypto.*;
import peergos.shared.ipfs.api.*;
import peergos.shared.storage.ContentAddressedStorage;
import com.sun.net.httpserver.*;

import java.io.*;
import java.util.*;
import java.util.function.*;
import java.util.stream.*;

public class DHTHandler implements HttpHandler
{
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
        if (query.startsWith("?"))
            query = query.substring(1);
        String[] parts = query.split("&");
        Map<String, List<String>> res = new HashMap<>();
        for (String part: parts) {
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
        try {
            String path = httpExchange.getRequestURI().getPath();
            if (! path.startsWith(apiPrefix))
                throw new IllegalStateException("Unsupported api version, required: " + apiPrefix);
            path = path.substring(apiPrefix.length());
            Map<String, List<String>> params = parseQuery(httpExchange.getRequestURI().getQuery());
            List<String> args = params.get("arg");
            Function<String, String> last = key -> params.get(key).get(params.get(key).size() - 1);

            switch (path) {
                case "object/new":{
                    UserPublicKey writer = UserPublicKey.fromString(last.apply("writer"));
                    dht._new(writer).thenAccept(newHash -> {
                        Map res = new HashMap();
                        res.put("Hash", newHash.toBase58());
                        // don't cache EMPTY multihash as it will change if the internal IPFS serialization format changes
                        replyJson(httpExchange, JSONParser.toString(res), Optional.empty());
                    });
                    break;
                }
                case "object/patch/add-link":{
                    UserPublicKey writer = UserPublicKey.fromString(last.apply("writer"));
                    Multihash hash = Multihash.fromBase58(args.get(0));
                    String label = args.get(1);
                    Multihash targetHash = Multihash.fromBase58(args.get(2));
                    dht.addLink(writer, hash, label, targetHash)
                            .thenAccept(resultHash -> {
                                Map res = new HashMap();
                                res.put("Hash", resultHash.toBase58());
                                replyJson(httpExchange, JSONParser.toString(res), Optional.empty());
                            });
                    break;
                }
                case "object/patch/set-data": {
                    UserPublicKey writer = UserPublicKey.fromString(last.apply("writer"));
                    Multihash hash = Multihash.fromBase58(args.get(0));
                    String boundary = httpExchange.getRequestHeaders().get("Content-Type")
                            .stream()
                            .filter(s -> s.contains("boundary="))
                            .map(s -> s.substring(s.indexOf("=") + 1))
                            .findAny()
                            .get();
                    byte[] data = MultipartReceiver.extractFile(httpExchange.getRequestBody(), boundary);
                    dht.setData(writer, hash, data).thenAccept(h -> {
                        Map<String, Object> json = new TreeMap<>();
                        json.put("Hash", h.toBase58());
                        replyJson(httpExchange, JSONParser.toString(json), Optional.empty());
                    });
                    break;
                }
                case "object/get":{
                    Multihash hash = Multihash.fromBase58(args.get(0));
                    dht.getObject(hash)
                            .thenAccept(opt -> replyJson(httpExchange,
                                    opt.map(m -> m.toJson(Optional.of(hash))).orElse(""), Optional.of(hash)));
                    break;
                }
                case "object/data": {
                    Multihash hash = Multihash.fromBase58(args.get(0));
                    dht.getData(hash).thenAccept(opt -> replyBytes(httpExchange, opt.orElse(new byte[0]), Optional.of(hash)));
                    break;
                }
                case "pin/add": {
                    Multihash hash = Multihash.fromBase58(args.get(0));
                    dht.recursivePin(hash).thenAccept(pinned -> {
                        Map<String, Object> json = new TreeMap<>();
                        json.put("Pins", pinned.stream().map(h -> h.toBase58()).collect(Collectors.toList()));
                        replyJson(httpExchange, JSONParser.toString(json), Optional.empty());
                    });
                    break;
                }
                case "pin/rm": {
                    boolean recursive = params.containsKey("r") && Boolean.parseBoolean(last.apply("r"));
                    if (!recursive)
                        throw new IllegalStateException("Unimplemented: non recursive unpin!");
                    Multihash hash = Multihash.fromBase58(args.get(0));
                    dht.recursiveUnpin(hash).thenAccept(unpinned -> {
                        Map<String, Object> json = new TreeMap<>();
                        json.put("Pins", unpinned.stream().map(h -> h.toBase58()).collect(Collectors.toList()));
                        replyJson(httpExchange, JSONParser.toString(json), Optional.empty());
                    });
                    break;
                }
                default: {
                    httpExchange.sendResponseHeaders(404, 0);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void replyJson(HttpExchange exchange, String json, Optional<Multihash> key) {
        try {
            if (key.isPresent()) {
                exchange.getResponseHeaders().set("Cache-Control", "public, max-age=31622400 immutable");
                exchange.getResponseHeaders().set("ETag", key.get().toBase58());
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
                exchange.getResponseHeaders().set("ETag", key.get().toBase58());
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
