package peergos.server.net;

import peergos.shared.crypto.*;
import peergos.shared.ipfs.api.Multihash;
import peergos.shared.merklebtree.MerkleNode;
import peergos.shared.storage.ContentAddressedStorage;
import peergos.shared.util.*;
import com.sun.net.httpserver.*;

import java.io.*;
import java.util.*;
import java.util.function.*;

public class DHTHandler implements HttpHandler
{
    private final ContentAddressedStorage dht;

    public DHTHandler(ContentAddressedStorage dht) throws IOException
    {
        this.dht = dht;
    }

    @Override
    public void handle(HttpExchange httpExchange) throws IOException {
        try {
            String path = httpExchange.getRequestURI().getPath();
            if (path.startsWith("/dht/get")) {
                String ipfsPrefix = "/dht/get/ipfs/";
                if (!path.startsWith(ipfsPrefix))
                    httpExchange.sendResponseHeaders(404, 0);
                else {
                    Multihash key = Multihash.fromBase58(path.substring(ipfsPrefix.length()));
                    byte[] value = dht.get(key);
                    new GetSuccess(key, httpExchange).accept(value);
                }
            } else {
                InputStream in = httpExchange.getRequestBody();
                DataInputStream din = new DataInputStream(in);
                int type = din.readInt();
                if (type == 0) {
                    // PUT
                    byte[] value = Serialize.deserializeByteArray(din, ContentAddressedStorage.MAX_OBJECT_LENGTH);
                    byte[] owner = Serialize.deserializeByteArray(din, UserPublicKey.MAX_SIZE);
                    // TODO check we care about owner
                    List<Multihash> hashes = new ArrayList<>();
                    int nlinks = din.readInt();
                    for (int i = 0; i < nlinks; i++)
                        hashes.add(new Multihash(Serialize.deserializeByteArray(din, 1024)));

                    SortedMap<String, Multihash> namedLinks = new TreeMap<>();
                    for (int i = 0; i < hashes.size(); i++)
                        namedLinks.put(Integer.toString(i), hashes.get(i));
                    MerkleNode obj = new MerkleNode(value, namedLinks);
                    byte[] put = dht.put(obj).toBytes();
                    new PutSuccess(httpExchange).accept(Optional.of(put));
                } else {
                    httpExchange.sendResponseHeaders(404, 0);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        }
    }

    private static class PutSuccess implements Consumer<Optional<byte[]>>
    {
        private final HttpExchange exchange;

        private PutSuccess(HttpExchange exchange)
        {
            this.exchange = exchange;
        }

        @Override
        public void accept(Optional<byte[]> result) {
            try {
                exchange.sendResponseHeaders(200, 0);
                DataOutputStream dout = new DataOutputStream(exchange.getResponseBody());
                dout.writeInt(result.isPresent() ? 1 : 0); // success
                if (result.isPresent())
                    Serialize.serialize(result.get(), dout);
                dout.flush();
                dout.close();
            } catch (IOException e)
            {
                e.printStackTrace();
            }
        }
    }

    private static class GetSuccess implements Consumer<byte[]>
    {
        private final HttpExchange exchange;
        private final Multihash key;

        private GetSuccess(Multihash key, HttpExchange exchange)
        {
            this.key = key;
            this.exchange = exchange;
        }

        @Override
        public void accept(byte[] value) {
            try {
                exchange.getResponseHeaders().set("Cache-Control", "public, max-age=31622400 immutable");
                exchange.getResponseHeaders().set("ETag", key.toBase58());
                exchange.sendResponseHeaders(200, 0);
                DataOutputStream dout = new DataOutputStream(exchange.getResponseBody());
                dout.writeInt(1); // success
                Serialize.serialize(value, dout);
                dout.flush();
                dout.close();
            } catch (IOException e)
            {
                e.printStackTrace();
            }
        }
    }
}
