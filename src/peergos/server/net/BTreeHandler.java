package peergos.server.net;

import com.sun.net.httpserver.*;
import peergos.corenode.*;
import peergos.crypto.*;
import peergos.server.merklebtree.*;
import peergos.server.storage.ContentAddressedStorage;
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
                byte[] sharingKey = Serialize.deserializeByteArray(din, UserPublicKey.MAX_SIZE);
                byte[] mapKey = Serialize.deserializeByteArray(din, 32);
                byte[] value = Serialize.deserializeByteArray(din, ContentAddressedStorage.MAX_OBJECT_LENGTH);
                System.out.println("Put mapkey: "+new ByteArrayWrapper(mapKey)+ " -> "+new ByteArrayWrapper(value));
                try {
                    byte[] raw = core.getMetadataBlob(sharingKey);
                    byte[] rootHash = raw.length == 0 ? new byte[0] : raw;
                    MerkleBTree btree = MerkleBTree.create(rootHash, dht);
                    byte[] newRoot = btree.put(mapKey, value);
                    ByteArrayOutputStream bout = new ByteArrayOutputStream();
                    DataOutputStream dout = new DataOutputStream(bout);
                    Serialize.serialize(rootHash, dout);
                    Serialize.serialize(newRoot, dout);
                    new ModifySuccess(httpExchange).accept(Optional.of(bout.toByteArray()));
                } catch (Exception e) {
                    e.printStackTrace();
                    new ModifySuccess(httpExchange).accept(Optional.empty());
                }
            } else if (type == 1) {
                // GET
                byte[] sharingKey = Serialize.deserializeByteArray(din, UserPublicKey.MAX_SIZE);
                byte[] mapKey = Serialize.deserializeByteArray(din, 64);
                System.out.println("Get mapkey: "+new ByteArrayWrapper(mapKey));
                try {
                    byte[] rootHash = core.getMetadataBlob(sharingKey);
                    MerkleBTree btree = MerkleBTree.create(rootHash, dht);
                    byte[] value = btree.get(mapKey);
                    new GetSuccess(httpExchange).accept(value);
                } catch (Exception e) {
                    e.printStackTrace();
                    new GetSuccess(httpExchange).accept(new byte[0]);
                }
            } else if (type == 2) {
                // DELETE
                byte[] sharingKey = Serialize.deserializeByteArray(din, UserPublicKey.MAX_SIZE);
                byte[] mapKey = Serialize.deserializeByteArray(din, 64);
                System.out.println("Deleted mapkey: "+new ByteArrayWrapper(mapKey));
                try {
                    byte[] rootHash = core.getMetadataBlob(sharingKey);
                    MerkleBTree btree = MerkleBTree.create(rootHash, dht);
                    byte[] newRoot = btree.delete(mapKey);
                    ByteArrayOutputStream bout = new ByteArrayOutputStream();
                    DataOutputStream dout = new DataOutputStream(bout);
                    Serialize.serialize(rootHash, dout);
                    Serialize.serialize(newRoot, dout);
                    new ModifySuccess(httpExchange).accept(Optional.of(bout.toByteArray()));
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
