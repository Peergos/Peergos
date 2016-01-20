package peergos.server.net;

import org.ipfs.api.*;
import peergos.crypto.*;
import peergos.server.merklebtree.MerkleNode;
import peergos.server.storage.ContentAddressedStorage;
import peergos.user.fs.*;
import peergos.util.*;
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
                for (int i=0; i < hashes.size(); i++)
                    namedLinks.put(Integer.toString(i), hashes.get(i));
                MerkleNode obj = new MerkleNode(value, namedLinks);
                byte[] put = dht.put(obj).toBytes();
                new PutSuccess(httpExchange).accept(Optional.of(put));
            } else if (type == 1) {
                // GET
                byte[] key = Serialize.deserializeByteArray(din, 64);
                byte[] value = dht.get(key);
                new GetSuccess(key, httpExchange).accept(value);
            } else {
                httpExchange.sendResponseHeaders(404, 0);
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
        private final byte[] key;

        private GetSuccess(byte[] key, HttpExchange exchange)
        {
            this.key = key;
            this.exchange = exchange;
        }

        @Override
        public void accept(byte[] value) {
            try {
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
