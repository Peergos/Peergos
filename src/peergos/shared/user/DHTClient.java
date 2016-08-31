package peergos.shared.user;

import peergos.shared.crypto.*;
import peergos.shared.ipfs.api.Multihash;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.io.*;
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

            byte[] res = poster.postUnzip("dht/put", bout.toByteArray());
            DataInputStream din = new DataInputStream(new ByteArrayInputStream(res));
            int success = din.readInt();
            if (success != 1)
                throw new IOException("Couldn't add data to DHT!");
            return new Multihash(Serialize.deserializeByteArray(din, 256));
        }

        @Override
        public Optional<byte[]> get(Multihash key) throws IOException {
            byte[] res = poster.get("dht/get/ipfs/" + key.toBase58());
            DataInputStream din = new DataInputStream(new ByteArrayInputStream(res));
            int success = din.readInt();
            if (success != 1)
                return Optional.empty();
            return Optional.of(Serialize.deserializeByteArray(din, Chunk.MAX_SIZE));
        }
    }

    class CachingDHTClient implements DHTClient {
        private final LRUCache<Multihash, byte[]> cache;
        private final DHTClient target;
        private final int maxValueSize;

        public CachingDHTClient(DHTClient target, int cacheSize, int maxValueSize) {
            this.target = target;
            this.cache = new LRUCache<>(cacheSize);
            this.maxValueSize = maxValueSize;
        }

        @Override
        public Multihash put(byte[] value, UserPublicKey writer, List<Multihash> links) throws IOException {
            return target.put(value, writer, links);
        }

        @Override
        public Optional<byte[]> get(Multihash key) throws IOException {
            if (cache.containsKey(key))
                return Optional.of(cache.get(key));
            Optional<byte[]> value = target.get(key);
            if (value.isPresent() && value.get().length < maxValueSize)
                cache.put(key, value.get());
            return value;
        }
    }
}
