package peergos.server.net;
import java.util.logging.*;

import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
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

public class DHTHandler implements HttpHandler {
	private static final Logger LOG = Logger.getGlobal();

    private static final boolean LOGGING = true;
    private final ContentAddressedStorage dht;
    private final BiFunction<PublicKeyHash, Integer, Boolean> keyFilter;
    private final String apiPrefix;

    public DHTHandler(ContentAddressedStorage dht, BiFunction<PublicKeyHash, Integer, Boolean> keyFilter, String apiPrefix) throws IOException
    {
        this.dht = dht;
        this.keyFilter = keyFilter;
        this.apiPrefix = apiPrefix;
    }

    public DHTHandler(ContentAddressedStorage dht, BiFunction<PublicKeyHash, Integer, Boolean> keyFilter) throws IOException {
        this(dht, keyFilter, "/api/v0/");
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
                    PublicKeyHash writerHash = PublicKeyHash.fromString(last.apply("writer"));
                    List<byte[]> signatures = Arrays.stream(last.apply("signatures").split(","))
                            .map(ArrayOps::hexToBytes)
                            .collect(Collectors.toList());
                    String boundary = httpExchange.getRequestHeaders().get("Content-Type")
                            .stream()
                            .filter(s -> s.contains("boundary="))
                            .map(s -> s.substring(s.indexOf("=") + 1))
                            .findAny()
                            .get();
                    List<byte[]> data = MultipartReceiver.extractFiles(httpExchange.getRequestBody(), boundary);
                    boolean isRaw = last.apply("format").equals("raw");

                    // check writer is allowed to write to this server, and check their free space
                    if (! keyFilter.apply(writerHash, data.stream().mapToInt(x -> x.length).sum()))
                        throw new IllegalStateException("Key not allowed to write to this server: " + writerHash);

                    // Get the actual key, unless this is the initial write of the signing key during sign up
                    // In the initial put of a signing key during sign up the key signs itself (we still check the hash
                    // against the core node)
                    Supplier<PublicSigningKey> fromDht = () -> {
                        try {
                            return PublicSigningKey.fromCbor(dht.get(writerHash.multihash).get().get());
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    };
                    Supplier<PublicSigningKey> inBandOrDht = () -> {
                        try {
                            PublicSigningKey candidateKey = PublicSigningKey.fromByteArray(data.get(0));
                            PublicKeyHash calculatedHash = ContentAddressedStorage.hashKey(candidateKey);
                            if (calculatedHash.equals(writerHash)) {
                                candidateKey.unsignMessage(ArrayOps.concat(signatures.get(0), data.get(0)));
                                return candidateKey;
                            }
                        } catch (Throwable e) {
                            // If signature is not valid then the signing key has already been written, retrieve it
                            // This happens for the boxing key during sign up for example
                        }
                        return fromDht.get();
                    };
                    PublicSigningKey writer = data.size() > 1 ? fromDht.get() : inBandOrDht.get();

                    // verify signatures
                    for (int i = 0; i < data.size(); i++) {
                        byte[] signature = signatures.get(i);
                        byte[] unsigned = writer.unsignMessage(ArrayOps.concat(signature, data.get(i)));
                        if (!Arrays.equals(unsigned, data.get(i)))
                            throw new IllegalStateException("Invalid signature for block!");
                    }

                    (isRaw ?
                            dht.putRaw(writerHash, signatures, data) :
                            dht.put(writerHash, signatures, data)).thenAccept(hashes -> {
                        List<Object> json = hashes.stream().map(h -> wrapHash(h)).collect(Collectors.toList());
                        // make stream of JSON objects
                        String jsonStream = json.stream().map(m -> JSONParser.toString(m)).reduce("", (a, b) -> a + b);
                        replyJson(httpExchange, jsonStream, Optional.empty());
                    }).exceptionally(Futures::logError);
                    break;
                }
                case "block/get":{
                    Multihash hash = Cid.decode(args.get(0));
                    (hash instanceof Cid && ((Cid) hash).codec == Cid.Codec.Raw ?
                            dht.getRaw(hash) :
                            dht.get(hash).thenApply(opt -> opt.map(CborObject::toByteArray)))
                            .thenAccept(opt -> replyBytes(httpExchange,
                                    opt.orElse(new byte[0]), opt.map(x -> hash)))
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
            LOG.severe("Error handling " +httpExchange.getRequestURI());
            LOG.log(Level.WARNING, e.getMessage(), e);
            replyError(httpExchange, e);
        } finally {
            httpExchange.close();
            long t2 = System.currentTimeMillis();
            if (LOGGING)
                LOG.info("DHT Handler handled " + path + " query in: " + (t2 - t1) + " mS");
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
            exchange.getResponseHeaders().set("Trailer", t.getMessage());
            exchange.sendResponseHeaders(400, -1);
        } catch (IOException e)
        {
            LOG.log(Level.WARNING, e.getMessage(), e);
        }
    }

    private static void replyJson(HttpExchange exchange, String json, Optional<Multihash> key) {
        try {
            if (key.isPresent()) {
                exchange.getResponseHeaders().set("Cache-Control", "public, max-age=31622400 immutable");
                exchange.getResponseHeaders().set("ETag", "\"" + key.get().toString() + "\"");
            }
            byte[] raw = json.getBytes();
            exchange.sendResponseHeaders(200, raw.length);
            DataOutputStream dout = new DataOutputStream(exchange.getResponseBody());
            dout.write(raw);
            dout.flush();
            dout.close();
        } catch (IOException e)
        {
            LOG.log(Level.WARNING, e.getMessage(), e);
        }
    }

    private static void replyBytes(HttpExchange exchange, byte[] body, Optional<Multihash> key) {
        try {
            if (key.isPresent()) {
                exchange.getResponseHeaders().set("Cache-Control", "public, max-age=31622400 immutable");
                exchange.getResponseHeaders().set("ETag", "\"" + key.get().toString() + "\"");
            }
            exchange.sendResponseHeaders(200, body.length);
            DataOutputStream dout = new DataOutputStream(exchange.getResponseBody());
            dout.write(body);
            dout.flush();
            dout.close();
        } catch (IOException e)
        {
            LOG.log(Level.WARNING, e.getMessage(), e);
        }
    }
}
