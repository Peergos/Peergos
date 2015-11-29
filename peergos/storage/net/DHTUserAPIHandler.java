package peergos.storage.net;

import peergos.crypto.*;
import peergos.storage.merklebtree.*;
import peergos.user.fs.*;
import peergos.util.*;
import com.sun.net.httpserver.*;

import java.io.*;
import java.util.*;
import java.util.function.*;

public class DHTUserAPIHandler implements HttpHandler
{
    private final ContentAddressedStorage dht;

    public DHTUserAPIHandler(ContentAddressedStorage dht) throws IOException
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
                byte[] value = Serialize.deserializeByteArray(din, Fragment.SIZE);
                byte[] owner = Serialize.deserializeByteArray(din, UserPublicKey.SIZE);
                // TODO check we care about owner
                byte[] put = dht.put(value);
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
