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

        private final HttpPoster poster;

        public HTTP(HttpPoster poster) {
            this.poster = poster;
        }

        @Override
        public Multihash put(byte[] value, UserPublicKey writer, List<Multihash> links) throws IOException {
            ByteArrayOutputStream bout  =new ByteArrayOutputStream();
            DataOutputStream dout = new DataOutputStream(bout);

            dout.writeInt(0); // PUT message
            Serialize.serialize(value, dout);
            Serialize.serialize(writer.toUserPublicKey().serialize(), dout);
            dout.writeInt(links.size());
            for (Multihash hash: links)
                Serialize.serialize(hash.toBytes(), dout);
            dout.flush();

            byte[] res = poster.post("dht/put", bout.toByteArray());
            DataInputStream din = new DataInputStream(new ByteArrayInputStream(res));
            int success = din.readInt();
            if (success != 1)
                throw new IOException("Couldn't add data to DHT!");
            return new Multihash(Serialize.deserializeByteArray(din, 256));
        }

        @Override
        public Optional<byte[]> get(Multihash key) throws IOException {
            ByteArrayOutputStream bout  =new ByteArrayOutputStream();
            DataOutputStream dout = new DataOutputStream(bout);

            dout.writeInt(1); // GET message
            Serialize.serialize(key.toBytes(), dout);
            dout.flush();

            byte[] res = poster.post("dht/get", bout.toByteArray());
            DataInputStream din = new DataInputStream(new ByteArrayInputStream(res));
            int success = din.readInt();
            if (success != 1)
                return Optional.empty();
            return Optional.of(Serialize.deserializeByteArray(din, Chunk.MAX_SIZE));
        }
    }
}
