package peergos.user;

import org.ipfs.api.*;
import peergos.crypto.*;
import peergos.user.fs.*;
import peergos.util.*;

import java.io.*;
import java.net.*;
import java.util.*;

public interface DHTClient {

    Multihash put(byte[] value, UserPublicKey writer, List<Multihash> links) throws IOException;

    Optional<byte[]> get(Multihash key) throws IOException;

    class HTTP implements DHTClient {

        private final URL dht;

        public HTTP(URL dht) {
            this.dht = dht;
        }

        public URL buildURL(String method) throws IOException {
            try {
                return new URL(dht, method);
            } catch (MalformedURLException mexican) {
                throw new IOException(mexican);
            }
        }

        @Override
        public Multihash put(byte[] value, UserPublicKey writer, List<Multihash> links) throws IOException {
            HttpURLConnection conn = null;
            try
            {
                conn = (HttpURLConnection) buildURL("dht/put").openConnection();
                conn.setDoInput(true);
                conn.setDoOutput(true);
                DataOutputStream dout = new DataOutputStream(conn.getOutputStream());

                dout.writeInt(0); // PUT message
                Serialize.serialize(value, dout);
                Serialize.serialize(writer.toUserPublicKey().serialize(), dout);
                dout.writeInt(links.size());
                for (Multihash hash: links)
                    Serialize.serialize(hash.toBytes(), dout);
                dout.flush();

                DataInputStream din = new DataInputStream(conn.getInputStream());
                int success = din.readInt();
                if (success != 1)
                    throw new IOException("Couldn't add data to DHT!");
                return new Multihash(Serialize.deserializeByteArray(din, 256));
            } finally {
                if (conn != null)
                    conn.disconnect();
            }
        }

        @Override
        public Optional<byte[]> get(Multihash key) throws IOException {
            HttpURLConnection conn = null;
            try
            {
                conn = (HttpURLConnection) buildURL("dht/get").openConnection();
                conn.setDoInput(true);
                conn.setDoOutput(true);
                DataOutputStream dout = new DataOutputStream(conn.getOutputStream());

                dout.writeInt(1); // GET message
                Serialize.serialize(key.toBytes(), dout);
                dout.flush();

                DataInputStream din = new DataInputStream(conn.getInputStream());
                int success = din.readInt();
                if (success != 1)
                    return Optional.empty();
                return Optional.of(Serialize.deserializeByteArray(din, Chunk.MAX_SIZE));
            } finally {
                if (conn != null)
                    conn.disconnect();
            }
        }
    }
}
