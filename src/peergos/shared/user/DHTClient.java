package peergos.shared.user;

import peergos.shared.crypto.*;
import peergos.shared.ipfs.api.Multihash;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public interface DHTClient {

    CompletableFuture<Multihash> put(byte[] value, UserPublicKey writer, List<Multihash> links) throws IOException;

    CompletableFuture<Optional<byte[]>> get(Multihash key) throws IOException;

    class HTTP implements DHTClient {

        private final HttpPoster poster;

        public HTTP(HttpPoster poster) {
            this.poster = poster;
        }

        @Override
        public CompletableFuture<Multihash> put(byte[] value, UserPublicKey writer, List<Multihash> links) throws IOException {
            ByteArrayOutputStream bout  =new ByteArrayOutputStream();
            DataOutputStream dout = new DataOutputStream(bout);

            dout.writeInt(0); // PUT message
            Serialize.serialize(value, dout);
            Serialize.serialize(writer.toUserPublicKey().serialize(), dout);
            dout.writeInt(links.size());
            for (Multihash hash: links)
                Serialize.serialize(hash.toBytes(), dout);
            dout.flush();

            return poster.postUnzip("dht/put", bout.toByteArray()).thenApply(res -> {
                DataInputStream din = new DataInputStream(new ByteArrayInputStream(res));
                try {
                    int success = din.readInt();
                    if (success != 1)
                        throw new IOException("Couldn't add data to DHT!");
                    return new Multihash(Serialize.deserializeByteArray(din, 256));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }

        @Override
        public CompletableFuture<Optional<byte[]>> get(Multihash key) throws IOException {
            return poster.get("dht/get/ipfs/" + key.toBase58()).thenApply(res -> {
                DataInputStream din = new DataInputStream(new ByteArrayInputStream(res));
                try {
                    int success = din.readInt();
                    if (success != 1)
                        return Optional.empty();
                    return Optional.of(Serialize.deserializeByteArray(din, Chunk.MAX_SIZE));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
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
        public CompletableFuture<Multihash> put(byte[] value, UserPublicKey writer, List<Multihash> links) throws IOException {
            return target.put(value, writer, links);
        }

        @Override
        public CompletableFuture<Optional<byte[]>> get(Multihash key) throws IOException {
            if (cache.containsKey(key))
                return CompletableFuture.completedFuture(Optional.of(cache.get(key)));
            return target.get(key).thenApply(value -> {
                if (value.isPresent() && value.get().length < maxValueSize)
                    cache.put(key, value.get());
                return value;
            });
        }
    }
}
