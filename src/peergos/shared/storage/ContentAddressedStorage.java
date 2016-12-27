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

    CompletableFuture<Multihash> emptyObject(UserPublicKey writer);

    CompletableFuture<Multihash> setData(UserPublicKey writer, Multihash object, byte[] data);

    CompletableFuture<Multihash> addLink(UserPublicKey writer, Multihash object, String label, Multihash linkTarget);

    CompletableFuture<Optional<byte[]>> getData(Multihash object);

    CompletableFuture<Optional<MerkleNode>> getObject(Multihash object);

    /**
     *
     * @param object
     * @return a hash of the stored object
     */
    default CompletableFuture<Multihash> put(UserPublicKey writer, MerkleNode object) {
        UserPublicKey publicWriter = writer.toUserPublicKey();
        return emptyObject(publicWriter)
                .thenCompose(EMPTY -> setData(publicWriter, EMPTY, object.data))
                .thenCompose(hash -> Futures.reduceAll(
                        object.links,
                        hash,
                        (h, e) -> addLink(publicWriter, h, e.label, e.target),
                        (a, b) -> {throw new IllegalStateException();}
        ));
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

        private static MerkleNode getObject(byte[] raw) {
            try {
                return MerkleNode.deserialize(raw);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        private static String encode(String component) {
            try {
                return URLEncoder.encode(component, "UTF-8");
            } catch (UnsupportedEncodingException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public CompletableFuture<Multihash> emptyObject(UserPublicKey writer) {
            return poster.get(apiPrefix + "object/new?stream-channels=true"+ "&writer=" + encode(writer.toUserPublicKey().toString()))
                    .thenApply(HTTP::getObjectHash);
        }

        @Override
        public CompletableFuture<Multihash> setData(UserPublicKey writer, Multihash base, byte[] data) {
            return poster.postMultipart(apiPrefix + "object/patch/set-data?arg=" + base.toBase58()
                    + "&writer=" + encode(writer.toUserPublicKey().toString()), Arrays.asList(data))
                    .thenApply(HTTP::getObjectHash);
        }

        @Override
        public CompletableFuture<Multihash> addLink(UserPublicKey writer, Multihash base, String label, Multihash linkTarget) {
            return poster.get(apiPrefix + "object/patch/add-link?arg=" + base.toBase58()
                    + "&arg=" + label + "&arg=" + linkTarget.toBase58()
                    + "&writer=" + encode(writer.toUserPublicKey().toString()))
                    .thenApply(HTTP::getObjectHash);
        }

        @Override
        public CompletableFuture<Optional<MerkleNode>> getObject(Multihash hash) {
            return poster.get(apiPrefix + "object/get?stream-channels=true&arg=" + hash.toBase58())
                    .thenApply(raw -> Optional.of(getObject(raw)));
        }

        @Override
        public CompletableFuture<Optional<byte[]>> getData(Multihash key) {
            return poster.get(apiPrefix + "object/data?stream-channels=true&arg=" + key.toBase58())
                    .thenApply(bytes -> Optional.of(bytes));
        }

//        @Override
//        public CompletableFuture<Multihash> put(UserPublicKey writer, MerkleNode object) {
//            // TODO implement using CBOR to save round trips
//            throw new IllegalStateException("Unimplemented!");
//        }

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
        private final LRUCache<Multihash, byte[]> cache;
        private final ContentAddressedStorage target;
        private final int maxValueSize;

        public CachingDHTClient(ContentAddressedStorage target, int cacheSize, int maxValueSize) {
            this.target = target;
            this.cache = new LRUCache<>(cacheSize);
            this.maxValueSize = maxValueSize;
        }

        @Override
        public CompletableFuture<Multihash> emptyObject(UserPublicKey writer) {
            return target.emptyObject(writer);
        }

        @Override
        public CompletableFuture<Multihash> setData(UserPublicKey writer, Multihash base, byte[] data) {
            return target.setData(writer, base, data);
        }

        @Override
        public CompletableFuture<Multihash> addLink(UserPublicKey writer, Multihash base, String label, Multihash linkTarget) {
            return target.addLink(writer, base, label, linkTarget);
        }

        @Override
        public CompletableFuture<Optional<MerkleNode>> getObject(Multihash hash) {
            return target.getObject(hash).thenApply(object -> {
                if (object.isPresent()) {
                    byte[] raw = object.get().serialize();
                    if (raw.length < maxValueSize)
                        cache.put(hash, raw);
                }
                return object;
            });
        }

        @Override
        public CompletableFuture<Optional<byte[]>> getData(Multihash hash) {
            return getObject(hash)
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
