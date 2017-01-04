package peergos.shared.storage;

import peergos.shared.crypto.*;
import peergos.shared.ipfs.api.*;
import peergos.shared.merklebtree.MerkleNode;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

public interface ContentAddressedStorage {

    int MAX_OBJECT_LENGTH  = 1024*256;

    CompletableFuture<List<Multihash>> put(UserPublicKey writer, List<byte[]> blocks);

    CompletableFuture<Optional<byte[]>> getData(Multihash object);

    CompletableFuture<Optional<MerkleNode>> get(Multihash object);

    /**
     *
     * @param object
     * @return a hash of the stored object
     */
    default CompletableFuture<Multihash> put(UserPublicKey writer, MerkleNode object) {
        UserPublicKey publicWriter = writer.toUserPublicKey();
        return put(publicWriter, Arrays.asList(object.serialize())).thenApply(list -> list.get(0));
    }

    default CompletableFuture<Multihash> put(UserPublicKey writer, byte[] data, List<Multihash> links) {
        return put(writer.toUserPublicKey(), new MerkleNode(data,
                links.stream()
                        .map(l -> new MerkleNode.Link(l.toBase58(), l))
                        .collect(Collectors.toList())));
    }

    default CompletableFuture<Multihash> put(UserPublicKey writer, byte[] data) {
        return put(writer, new MerkleNode(data, Collections.emptyList()));
    }

    CompletableFuture<List<Multihash>> recursivePin(Multihash h);

    CompletableFuture<List<Multihash>> recursiveUnpin(Multihash h);

    class HTTP implements ContentAddressedStorage {

        private final HttpPoster poster;
        private final String apiPrefix = "api/v0/";

        public HTTP(HttpPoster poster) {
            this.poster = poster;
        }

        private static Multihash getObjectHash(byte[] raw) {
            Map json = (Map)JSONParser.parse(new String(raw));
            String hash = (String)json.get("Hash");
            if (hash == null)
                hash = (String)json.get("Key");
            return Multihash.fromBase58(hash);
        }

        private static Multihash getObjectHash(Object rawJson) {
            Map json = (Map)rawJson;
            String hash = (String)json.get("Hash");
            if (hash == null)
                hash = (String)json.get("Key");
            return Multihash.fromBase58(hash);
        }

        private static MerkleNode getObject(byte[] raw) {
            return MerkleNode.deserialize(raw);
        }

        private static String encode(String component) {
            try {
                return URLEncoder.encode(component, "UTF-8");
            } catch (UnsupportedEncodingException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public CompletableFuture<List<Multihash>> put(UserPublicKey writer, List<byte[]> blocks) {
            return poster.postMultipart(apiPrefix + "block/put?arg="
                    + "&writer=" + encode(writer.toUserPublicKey().toString()), blocks)
                    .thenApply(bytes -> JSONParser.parseStream(new String(bytes))
                            .stream()
                            .map(json -> getObjectHash(json))
                            .collect(Collectors.toList()));
        }

        @Override
        public CompletableFuture<Optional<MerkleNode>> get(Multihash hash) {
            return poster.get(apiPrefix + "block/get?stream-channels=true&arg=" + hash.toBase58())
                    .thenApply(raw -> Optional.of(getObject(raw)));
        }

        @Override
        public CompletableFuture<Optional<byte[]>> getData(Multihash key) {
            return get(key)
                    .thenApply(nodeOpt -> nodeOpt.map(node -> node.data));
        }

        @Override
        public CompletableFuture<List<Multihash>> recursivePin(Multihash hash) {
            return poster.get(apiPrefix + "pin/add?stream-channels=true&arg=" + hash.toBase58())
                    .thenApply(this::getPins);
        }

        @Override
        public CompletableFuture<List<Multihash>> recursiveUnpin(Multihash hash) {
            return poster.get(apiPrefix + "pin/rm?stream-channels=true&r=true&arg=" + hash.toBase58())
                    .thenApply(this::getPins);
        }

        private List<Multihash> getPins(byte[] raw) {
            Map res = (Map)JSONParser.parse(new String(raw));
            List<String> pins = (List<String>)res.get("Pins");
            return pins.stream().map(Multihash::fromBase58).collect(Collectors.toList());
        }
    }

    class CachingDHTClient implements ContentAddressedStorage {
        private final LRUCache<String, byte[]> cache;
        private final ContentAddressedStorage target;
        private final int maxValueSize;

        public CachingDHTClient(ContentAddressedStorage target, int cacheSize, int maxValueSize) {
            this.target = target;
            this.cache = new LRUCache<>(cacheSize);
            this.maxValueSize = maxValueSize;
        }

        @Override
        public CompletableFuture<List<Multihash>> put(UserPublicKey writer, List<byte[]> blocks) {
            return target.put(writer, blocks);
        }

        @Override
        public CompletableFuture<Optional<MerkleNode>> get(Multihash hash) {
            // somehow enabling this ram cache slows down logging by 3-4X...
            /*String cacheKey = hash.toBase58();
            if (cache.containsKey(cacheKey))
                return CompletableFuture.completedFuture(Optional.of(MerkleNode.deserialize(cache.get(cacheKey))));
                */
            return target.get(hash).thenApply(object -> {
                /*if (object.isPresent()) {
                    byte[] raw = object.get().serialize();
                    if (raw.length < maxValueSize)
                        cache.put(cacheKey, raw);
                }*/
                return object;
            });
        }

        @Override
        public CompletableFuture<Optional<byte[]>> getData(Multihash hash) {
            return get(hash)
                    .thenApply(opt -> opt.map(object -> object.data));
        }

        @Override
        public CompletableFuture<List<Multihash>> recursivePin(Multihash h) {
            return target.recursivePin(h);
        }

        @Override
        public CompletableFuture<List<Multihash>> recursiveUnpin(Multihash h) {
            return target.recursiveUnpin(h);
        }
    }
}
