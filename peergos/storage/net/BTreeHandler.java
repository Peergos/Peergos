package peergos.storage.net;

import com.sun.net.httpserver.*;
import peergos.corenode.*;
import peergos.crypto.*;
import peergos.storage.dht.*;
import peergos.storage.merklebtree.*;
import peergos.user.fs.*;
import peergos.util.*;

import java.io.*;
import java.util.*;
import java.util.function.*;

public class BTreeHandler implements HttpHandler
{
    private final CoreNode core;
    private final ContentAddressedStorage dht;

    public BTreeHandler(CoreNode core, ContentAddressedStorage dht) throws IOException
    {
        this.core = core;
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
                byte[] sharingKey = Serialize.deserializeByteArray(din, UserPublicKey.SIZE);
                byte[] mapKey = Serialize.deserializeByteArray(din, 32);
                byte[] value = Serialize.deserializeByteArray(din, Fragment.SIZE);

                try {
                    MerkleBTree btree = MerkleBTree.create(core.getMetadataBlob(sharingKey), dht);
                    byte[] newRoot = btree.put(mapKey, value);
                    new ModifySuccess(httpExchange).accept(Optional.of(newRoot));
                } catch (Exception e) {
                    e.printStackTrace();
                    new ModifySuccess(httpExchange).accept(Optional.empty());
                }
            } else if (type == 1) {
                // GET
                byte[] sharingKey = Serialize.deserializeByteArray(din, UserPublicKey.SIZE);
                byte[] mapKey = Serialize.deserializeByteArray(din, 64);
                try {
                    MerkleBTree btree = MerkleBTree.create(core.getMetadataBlob(sharingKey), dht);
                    byte[] value = btree.get(mapKey);
                    new GetSuccess(httpExchange).accept(value);
                } catch (Exception e) {
                    e.printStackTrace();
                    new GetSuccess(httpExchange).accept(new byte[0]);
                }
            } else if (type == 2) {
                // DELETE
                byte[] sharingKey = Serialize.deserializeByteArray(din, UserPublicKey.SIZE);
                byte[] mapKey = Serialize.deserializeByteArray(din, 64);
                try {
                    MerkleBTree btree = MerkleBTree.create(core.getMetadataBlob(sharingKey), dht);
                    byte[] newRoot = btree.delete(mapKey);
                    new ModifySuccess(httpExchange).accept(Optional.of(newRoot));
                } catch (Exception e) {
                    e.printStackTrace();
                    new ModifySuccess(httpExchange).accept(Optional.empty());
                }
            } else {
                httpExchange.sendResponseHeaders(404, 0);
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        }
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
