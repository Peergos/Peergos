package peergos.server.net;

import com.sun.net.httpserver.*;
import org.ipfs.api.Multihash;
import peergos.corenode.*;
import peergos.crypto.*;
import peergos.server.merklebtree.*;
import peergos.server.storage.ContentAddressedStorage;
import peergos.util.*;

import java.io.*;
import java.util.*;
import java.util.function.*;

public class BTreeHandlers
{
    private static final String PUT_STEM = "/btree/put";
    private static final String GET_STEM = "/btree/get";
    private static final String REMOVE_STEM = "/btree/delete";

    private class GetHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange httpExchange) throws IOException {
            InputStream in = httpExchange.getRequestBody();
            DataInputStream din = new DataInputStream(in);

            byte[] sharingKey = Serialize.deserializeByteArray(din, UserPublicKey.MAX_SIZE);
            byte[] mapKey = Serialize.deserializeByteArray(din, 64);
            System.out.println("Get mapkey: "+new ByteArrayWrapper(mapKey));
            try {
                MaybeMultihash rootHash = core.getMetadataBlob(UserPublicKey.fromByteArray(sharingKey));
                MerkleBTree btree = MerkleBTree.create(rootHash, dht);
                byte[] value = btree.get(mapKey).toBytes();
                new GetSuccess(httpExchange).accept(value);
            } catch (Exception e) {
                e.printStackTrace();
                new GetSuccess(httpExchange).accept(new byte[0]);
            }

        }
    }

    private class PutHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange httpExchange) throws IOException {
            InputStream in = httpExchange.getRequestBody();
            DataInputStream din = new DataInputStream(in);
                byte[] sharingKey = Serialize.deserializeByteArray(din, UserPublicKey.MAX_SIZE);
                byte[] mapKey = Serialize.deserializeByteArray(din, 32);
                byte[] value = Serialize.deserializeByteArray(din, ContentAddressedStorage.MAX_OBJECT_LENGTH);
                System.out.println("Put mapkey: " + new ByteArrayWrapper(mapKey) + " -> " + new ByteArrayWrapper(value));
                try {
                    MaybeMultihash rootHash = core.getMetadataBlob(UserPublicKey.fromByteArray(sharingKey));
                    MerkleBTree btree = MerkleBTree.create(rootHash, dht);
                    Multihash newRoot = btree.put(mapKey,
                            new Multihash(value));
                    ByteArrayOutputStream bout = new ByteArrayOutputStream();
                    DataOutputStream dout = new DataOutputStream(bout);
                    Serialize.serialize(rootHash.toBytes(), dout);
                    Serialize.serialize(newRoot.toBytes(), dout);
                    new ModifySuccess(httpExchange).accept(Optional.of(bout.toByteArray()));
                } catch (Exception e) {
                    e.printStackTrace();
                    new ModifySuccess(httpExchange).accept(Optional.empty());
                }
        }
    }

    private class RemoveHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange httpExchange) throws IOException {
            InputStream in = httpExchange.getRequestBody();
            DataInputStream din = new DataInputStream(in);

            byte[] sharingKey = Serialize.deserializeByteArray(din, UserPublicKey.MAX_SIZE);
            byte[] mapKey = Serialize.deserializeByteArray(din, 64);
            System.out.println("Deleted mapkey: "+new ByteArrayWrapper(mapKey));
            try {
                MaybeMultihash rootHash = core.getMetadataBlob(UserPublicKey.fromByteArray(sharingKey));
                MerkleBTree btree = MerkleBTree.create(rootHash, dht);
                Multihash newRoot = btree.delete(mapKey);
                ByteArrayOutputStream bout = new ByteArrayOutputStream();
                DataOutputStream dout = new DataOutputStream(bout);
                Serialize.serialize(rootHash.toBytes(),
                        dout);
                Serialize.serialize(newRoot.toBytes(),
                        dout);
                new ModifySuccess(httpExchange).accept(Optional.of(bout.toByteArray()));
            } catch (Exception e) {
                e.printStackTrace();
                new ModifySuccess(httpExchange).accept(Optional.empty());
            }

        }
    }

    private final CoreNode core;
    private final ContentAddressedStorage dht;
    private final Map<String, HttpHandler> handlerMap;

    public BTreeHandlers(CoreNode core, ContentAddressedStorage dht) throws IOException
    {
        this.core = core;
        this.dht = dht;

        Map<String, HttpHandler> map = new HashMap<>();
        map.put(PUT_STEM, new PutHandler());
        map.put(GET_STEM, new GetHandler());
        map.put(REMOVE_STEM, new RemoveHandler());

        this.handlerMap = Collections.unmodifiableMap(map);
    }

    public Map<String, HttpHandler> handlerMap() {
        return handlerMap;
    }

    private static class ModifySuccess implements Consumer<Optional<byte[]>>
    {
        private final HttpExchange exchange;

        private ModifySuccess(HttpExchange exchange)
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

        private GetSuccess(HttpExchange exchange)
        {
            this.exchange = exchange;
        }

        @Override
        public void accept(byte[] value) {
            try {
                exchange.sendResponseHeaders(200, 0);
                DataOutputStream dout = new DataOutputStream(exchange.getResponseBody());
                dout.writeInt(1); // success
                Serialize.serialize(value == null ? new byte[0] : value, dout);
                dout.flush();
                dout.close();
            } catch (IOException e)
            {
                e.printStackTrace();
            }
        }
    }
}
