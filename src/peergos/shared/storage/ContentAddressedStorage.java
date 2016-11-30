package peergos.shared.storage;

import peergos.shared.crypto.*;
import peergos.shared.ipfs.api.Multihash;
import peergos.shared.merklebtree.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

public interface ContentAddressedStorage {

    int MAX_OBJECT_LENGTH  = 1024*256;

    /**
     *
     * @param object
     * @return a hash of the stored object
     */
    CompletableFuture<Multihash> put(UserPublicKey writer, MerkleNode object);

    /**
     *
     * @param key the hash of a value previously stored
     * @return
     */
    CompletableFuture<Optional<byte[]>> get(Multihash key);

    default CompletableFuture<Multihash> put(UserPublicKey writer, byte[] data, List<Multihash> links) {
        return put(writer, new MerkleNode(data, links.stream().collect(Collectors.toMap(m -> m.toString(), m -> m))));
    }

    default CompletableFuture<Multihash> put(UserPublicKey writer, byte[] data) {
        return put(writer, new MerkleNode(data, Collections.emptyMap()));
    }

    CompletableFuture<Boolean> recursivePin(Multihash h);

    CompletableFuture<Boolean> recursiveUnpin(Multihash h);

    class HTTP implements ContentAddressedStorage {

        private final HttpPoster poster;

        public HTTP(HttpPoster poster) {
            this.poster = poster;
        }

        @Override
        public CompletableFuture<Multihash> put(UserPublicKey writer, byte[] value, List<Multihash> links) {
            ByteArrayOutputStream bout  =new ByteArrayOutputStream();
            DataOutputStream dout = new DataOutputStream(bout);

            try {
                dout.writeInt(0); // PUT message
                Serialize.serialize(value, dout);
                Serialize.serialize(writer.toUserPublicKey().serialize(), dout);
                dout.writeInt(links.size());
                for (Multihash hash : links)
                    Serialize.serialize(hash.toBytes(), dout);
                dout.flush();
            } catch (IOException e) {
                CompletableFuture<Multihash> err = new CompletableFuture<>();
                err.completeExceptionally(e);
                return err;
            }
            return poster.postUnzip("dht/put", bout.toByteArray()).thenApply(res -> {
                DataInputStream din = new DataInputStream(new ByteArrayInputStream(res));
                try {
                    int success = din.readInt();
                    if (success != 1)
                        throw new IOException("Couldn't add data to DHT!");
                    return new Multihash(Serialize.deserializeByteArray(din, 256));
                } catch (Exception e) {
                    System.out.println(e.getMessage());
                    throw new RuntimeException(e);
                }
            });
        }

        @Override
        public CompletableFuture<Optional<byte[]>> get(Multihash key) {
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

        @Override
        public CompletableFuture<Multihash> put(UserPublicKey writer, MerkleNode object) {
            return null;
        }

        @Override
        public CompletableFuture<Boolean> recursivePin(Multihash h) {
            return null;
        }

        @Override
        public CompletableFuture<Boolean> recursiveUnpin(Multihash h) {
            return null;
        }
    }

    class CachingDHTClient implements ContentAddressedStorage {
        private final LRUCache<Multihash, byte[]> cache;
        private final ContentAddressedStorage target;
        private final int maxValueSize;

        public CachingDHTClient(ContentAddressedStorage target, int cacheSize, int maxValueSize) {
            this.target = target;
            this.cache = new LRUCache<>(cacheSize);
            this.maxValueSize = maxValueSize;
        }

        @Override
        public CompletableFuture<Multihash> put(UserPublicKey writer, MerkleNode node) {
            return target.put(writer, node);
        }

        @Override
        public CompletableFuture<Optional<byte[]>> get(Multihash key) {
            if (cache.containsKey(key))
                return CompletableFuture.completedFuture(Optional.of(cache.get(key)));
            return target.get(key).thenApply(value -> {
                if (value.isPresent() && value.get().length < maxValueSize)
                    cache.put(key, value.get());
                return value;
            });
        }

        @Override
        public CompletableFuture<Boolean> recursivePin(Multihash h) {
            throw new IllegalStateException("Unimplemented recursivePin()!");
        }

        @Override
        public CompletableFuture<Boolean> recursiveUnpin(Multihash h) {
            throw new IllegalStateException("Unimplemented recursiveUnpin()!");
        }
    }
}
