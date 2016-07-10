package peergos.server.net;

import com.sun.net.httpserver.*;
import org.ipfs.api.Multihash;
import peergos.corenode.*;
import peergos.crypto.*;
import peergos.merklebtree.*;
import peergos.server.storage.ContentAddressedStorage;
import peergos.user.*;
import peergos.util.*;

import java.io.*;
import java.util.*;
import java.util.function.*;

public class BTreeHandlers
{
    public static boolean LOG = false;
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

            try {
                MaybeMultihash res = btree.get(UserPublicKey.fromByteArray(sharingKey), mapKey);
                byte[] value = res.toBytes();
                log("Btree::Get mapkey: "+new ByteArrayWrapper(mapKey) + " = "+ res);
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
            byte[] valueRaw = Serialize.deserializeByteArray(din, ContentAddressedStorage.MAX_OBJECT_LENGTH);
            Multihash value = new Multihash(valueRaw);

            try {
                PairMultihash rootCAS = btree.put(UserPublicKey.fromByteArray(sharingKey), mapKey, value);
                log("Btree::Put mapkey: " + new ByteArrayWrapper(mapKey) + " -> " + value + " newRoot="+rootCAS.right);

                ByteArrayOutputStream bout = new ByteArrayOutputStream();
                DataOutputStream dout = new DataOutputStream(bout);
                rootCAS.left.serialize(dout);
                rootCAS.right.serialize(dout);
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
            log("Btree::Deleted mapkey: "+new ByteArrayWrapper(mapKey));
            try {
                PairMultihash rootCAS = btree.remove(UserPublicKey.fromByteArray(sharingKey), mapKey);
                DataSink sink = new DataSink();
                rootCAS.left.serialize(sink);
                rootCAS.right.serialize(sink);
                new ModifySuccess(httpExchange).accept(Optional.of(sink.toByteArray()));
            } catch (Exception e) {
                e.printStackTrace();
                new ModifySuccess(httpExchange).accept(Optional.empty());
            }

        }
    }

    private final Btree btree;
    private final Map<String, HttpHandler> handlerMap;

    public BTreeHandlers(CoreNode core, ContentAddressedStorage dht) throws IOException
    {
        this.btree = new BtreeImpl(core, dht);

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
                    dout.write(result.get());
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
                dout.write(value == null ? new byte[0] : value);
                dout.flush();
                dout.close();
            } catch (IOException e)
            {
                e.printStackTrace();
            }
        }
    }

    private static void log(String msg) {
        if (LOG)
            System.out.println(msg);
    }
}
