package peergos.server.net;
import java.util.logging.*;

import peergos.server.AggregatedMetrics;
import peergos.server.util.*;

import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.api.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.storage.*;
import com.sun.net.httpserver.*;
import peergos.shared.util.*;

import static peergos.shared.storage.ContentAddressedStorage.HTTP.*;

import java.io.*;
import java.util.*;
import java.util.function.*;
import java.util.stream.*;

public class DHTHandler implements HttpHandler {
	private static final Logger LOG = Logging.LOG();

    private static final boolean LOGGING = true;
    private final ContentAddressedStorage dht;
    private final Hasher hasher;
    private final BiFunction<PublicKeyHash, Integer, Boolean> keyFilter;
    private final String apiPrefix;
    private final boolean isPublicServer;

    public DHTHandler(ContentAddressedStorage dht,
                      Hasher hasher,
                      BiFunction<PublicKeyHash, Integer, Boolean> keyFilter,
                      String apiPrefix,
                      boolean isPublicServer) {
        this.dht = dht;
        this.hasher = hasher;
        this.keyFilter = keyFilter;
        this.apiPrefix = apiPrefix;
        this.isPublicServer = isPublicServer;
    }

    public DHTHandler(ContentAddressedStorage dht,
                      Hasher hasher,
                      BiFunction<PublicKeyHash, Integer, Boolean> keyFilter,
                      boolean isPublicServer) {
        this(dht, hasher, keyFilter, "/api/v0/", isPublicServer);
    }

    @Override
    public void handle(HttpExchange httpExchange) {
        long t1 = System.currentTimeMillis();
        String path = httpExchange.getRequestURI().getPath();
        try {
            if (! HttpUtil.allowedQuery(httpExchange, isPublicServer)) {
                httpExchange.sendResponseHeaders(405, 0);
                return;
            }

            if (! path.startsWith(apiPrefix))
                throw new IllegalStateException("Unsupported api version, required: " + apiPrefix);
            path = path.substring(apiPrefix.length());
            // N.B. URI.getQuery() decodes the query string
            Map<String, List<String>> params = HttpUtil.parseQuery(httpExchange.getRequestURI().getQuery());
            List<String> args = params.get("arg");
            Function<String, String> last = key -> params.get(key).get(params.get(key).size() - 1);

            switch (path) {
                case BLOCKSTORE_PROPERTIES: {
                    dht.blockStoreProperties().thenAccept(p -> {
                        replyBytes(httpExchange, p.serialize(), Optional.empty());
                    }).exceptionally(Futures::logAndThrow).get();
                    break;
                }
                case AUTH_WRITES: {
                    PublicKeyHash ownerHash = PublicKeyHash.fromString(last.apply("owner"));
                    TransactionId tid = new TransactionId(last.apply("transaction"));
                    PublicKeyHash writerHash = PublicKeyHash.fromString(last.apply("writer"));
                    byte[] reqBody = Serialize.readFully(httpExchange.getRequestBody());
                    WriteAuthRequest req = WriteAuthRequest.fromCbor(CborObject.fromByteArray(reqBody));
                    List<byte[]> signatures = req.signatures;
                    List<Integer> blockSizes = req.sizes.stream()
                            .map(x -> x.intValue())
                            .collect(Collectors.toList());
                    boolean isRaw = Boolean.parseBoolean(last.apply("raw"));
                    dht.authWrites(ownerHash, writerHash, signatures, blockSizes, isRaw, tid).thenAccept(res -> {
                        replyBytes(httpExchange, new CborObject.CborList(res).serialize(), Optional.empty());
                    }).exceptionally(Futures::logAndThrow).get();
                    break;
                }
                case AUTH_READS: {
                    List<Multihash> blockHashes = Arrays.stream(last.apply("hashes").split(","))
                            .map(Cid::decode)
                            .collect(Collectors.toList());
                    dht.authReads(blockHashes).thenAccept(res -> {
                        replyBytes(httpExchange, new CborObject.CborList(res).serialize(), Optional.empty());
                    }).exceptionally(Futures::logAndThrow).get();
                    break;
                }
                case TRANSACTION_START: {
                    AggregatedMetrics.DHT_TRANSACTION_START.inc();
                    PublicKeyHash ownerHash = PublicKeyHash.fromString(last.apply("owner"));
                    dht.startTransaction(ownerHash).thenAccept(tid -> {
                        replyJson(httpExchange, tid.toString(), Optional.empty());
                    }).exceptionally(Futures::logAndThrow).get();
                    break;
                }
                case TRANSACTION_CLOSE: {
                    AggregatedMetrics.DHT_TRANSACTION_CLOSE.inc();
                    PublicKeyHash ownerHash = PublicKeyHash.fromString(last.apply("owner"));
                    TransactionId tid = new TransactionId(args.get(0));
                    dht.closeTransaction(ownerHash, tid).thenAccept(b -> {
                        replyJson(httpExchange, JSONParser.toString(b ? 1 : 0), Optional.empty());
                    }).exceptionally(Futures::logAndThrow).get();
                    break;
                }
                case CHAMP_GET: {
                    AggregatedMetrics.DHT_CHAMP_GET.inc();
                    PublicKeyHash ownerHash = PublicKeyHash.fromString(last.apply("owner"));
                    Multihash root = Cid.decode(args.get(0));
                    byte[] champKey = ArrayOps.hexToBytes(args.get(1));
                    dht.getChampLookup(ownerHash, root, champKey).thenAccept(blocks -> {
                        replyBytes(httpExchange, new CborObject.CborList(blocks.stream()
                                .map(CborObject.CborByteArray::new).collect(Collectors.toList())).serialize(), Optional.of(root));
                    }).exceptionally(Futures::logAndThrow).get();
                    break;
                }
                case BLOCK_PUT: {
                    AggregatedMetrics.DHT_BLOCK_PUT.inc();
                    PublicKeyHash ownerHash = PublicKeyHash.fromString(last.apply("owner"));
                    TransactionId tid = new TransactionId(last.apply("transaction"));
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
                            return dht.getSigningKey(writerHash).get().get();
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    };
                    Supplier<PublicSigningKey> inBandOrDht = () -> {
                        try {
                            PublicSigningKey candidateKey = PublicSigningKey.fromByteArray(data.get(0));
                            PublicKeyHash calculatedHash = ContentAddressedStorage.hashKey(candidateKey);
                            if (calculatedHash.equals(writerHash)) {
                                candidateKey.unsignMessage(signatures.get(0));
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
                        byte[] hash = hasher.sha256(data.get(i)).join();
                        byte[] unsigned = writer.unsignMessage(signature);
                        if (! Arrays.equals(unsigned, hash))
                            throw new IllegalStateException("Invalid signature for block!");
                    }

                    List<Multihash> hashes = (isRaw ?
                            dht.putRaw(ownerHash, writerHash, signatures, data, tid, x -> {}) :
                            dht.put(ownerHash, writerHash, signatures, data, tid)).get();
                    List<Object> json = hashes.stream()
                            .map(h -> wrapHash(h))
                            .collect(Collectors.toList());
                    // make stream of JSON objects
                    String jsonStream = json.stream()
                            .map(m -> JSONParser.toString(m))
                            .reduce("", (a, b) -> a + b);
                    replyJson(httpExchange, jsonStream, Optional.empty());
                    break;
                }
                case BLOCK_GET:{
                    AggregatedMetrics.DHT_BLOCK_GET.inc();
                    Multihash hash = Cid.decode(args.get(0));
                    (hash instanceof Cid && ((Cid) hash).codec == Cid.Codec.Raw ?
                            dht.getRaw(hash) :
                            dht.get(hash).thenApply(opt -> opt.map(CborObject::toByteArray)))
                            .thenAccept(opt -> replyBytes(httpExchange,
                                    opt.orElse(new byte[0]), opt.map(x -> hash)))
                            .exceptionally(Futures::logAndThrow).get();
                    break;
                }
                case BLOCK_STAT: {
                    AggregatedMetrics.DHT_BLOCK_STAT.inc();
                    Multihash block = Cid.decode(args.get(0));
                    dht.getSize(block).thenAccept(sizeOpt -> {
                        Map<String, Object> res = new HashMap<>();
                        res.put("Size", sizeOpt.orElse(0));
                        String json = JSONParser.toString(res);
                        replyJson(httpExchange, json, Optional.of(block));
                    }).exceptionally(Futures::logAndThrow).get();
                    break;
                }
                case REFS: {
                    AggregatedMetrics.DHT_BLOCK_REFS.inc();
                    Multihash block = Cid.decode(args.get(0));
                    dht.getLinks(block).thenAccept(links -> {
                        List<Object> json = links.stream().map(h -> wrapHash("Ref", h)).collect(Collectors.toList());
                        // make stream of JSON objects
                        String jsonStream = json.stream().map(m -> JSONParser.toString(m)).reduce("", (a, b) -> a + b);
                        replyJson(httpExchange, jsonStream, Optional.of(block));
                    }).exceptionally(Futures::logAndThrow).get();
                    break;
                }
                case ID: {
                    AggregatedMetrics.DHT_ID.inc();
                    dht.id().thenAccept(id -> {
                        Object json = wrapHash("ID", id);
                        replyJson(httpExchange, JSONParser.toString(json), Optional.empty());
                    }).exceptionally(Futures::logAndThrow).get();
                    break;
                }
                default: {
                    httpExchange.sendResponseHeaders(404, 0);
                }
            }
        } catch (RateLimitException e) {
            HttpUtil.replyErrorWithCode(httpExchange, 429, "Too Many Requests");
        } catch (Exception e) {
            LOG.severe("Error handling " +httpExchange.getRequestURI());
            LOG.log(Level.WARNING, e.getMessage(), e);
            HttpUtil.replyError(httpExchange, e);
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
