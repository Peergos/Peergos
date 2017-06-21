package peergos.shared.storage;

import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.api.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.io.ipfs.multiaddr.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.user.*;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

public interface ContentAddressedStorage {

    int MAX_OBJECT_LENGTH  = 1024*256;

    default CompletableFuture<Multihash> put(PublicKeyHash writer, byte[] block) {
        return put(writer, Arrays.asList(block)).thenApply(hashes -> hashes.get(0));
    }

    default CompletableFuture<Multihash> putRaw(PublicKeyHash writer, byte[] block) {
        return putRaw(writer, Arrays.asList(block)).thenApply(hashes -> hashes.get(0));
    }

    CompletableFuture<List<Multihash>> put(PublicKeyHash writer, List<byte[]> blocks);

    CompletableFuture<Optional<CborObject>> get(Multihash object);

    CompletableFuture<List<Multihash>> putRaw(PublicKeyHash writer, List<byte[]> blocks);

    CompletableFuture<Optional<byte[]>> getRaw(Multihash object);

    CompletableFuture<List<MultiAddress>> pinUpdate(Multihash existing, Multihash updated);

    CompletableFuture<List<Multihash>> recursivePin(Multihash h);

    CompletableFuture<List<Multihash>> recursiveUnpin(Multihash h);

    CompletableFuture<List<Multihash>> getLinks(Multihash root);

    CompletableFuture<Optional<Integer>> getSize(Multihash block);

    default CompletableFuture<PublicKeyHash> putSigningKey(PublicSigningKey key) {
        return put(PublicKeyHash.NULL, key.toCbor().toByteArray())
                .thenApply(PublicKeyHash::new);
    }

    default CompletableFuture<PublicKeyHash> putBoxingKey(PublicKeyHash controller, PublicBoxingKey key) {
        return put(controller, key.toCbor().toByteArray())
                .thenApply(PublicKeyHash::new);
    }

    default CompletableFuture<Optional<PublicSigningKey>> getSigningKey(PublicKeyHash hash) {
        return get(hash)
                .thenApply(opt -> opt.map(PublicSigningKey::fromCbor));
    }

    default CompletableFuture<Optional<PublicBoxingKey>> getBoxingKey(PublicKeyHash hash) {
        return get(hash)
                .thenApply(opt -> opt.map(PublicBoxingKey::fromCbor));
    }

    class HTTP implements ContentAddressedStorage {

        private final HttpPoster poster;
        private final String apiPrefix = "api/v0/";

        public HTTP(HttpPoster poster) {
            this.poster = poster;
        }

        private static Multihash getObjectHash(Object rawJson) {
            Map json = (Map)rawJson;
            String hash = (String)json.get("Hash");
            if (hash == null)
                hash = (String)json.get("Key");
            return Cid.decode(hash);
        }

        private static String encode(String component) {
            try {
                return URLEncoder.encode(component, "UTF-8");
            } catch (UnsupportedEncodingException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public CompletableFuture<List<Multihash>> put(PublicKeyHash writer, List<byte[]> blocks) {
            return put(writer, blocks, "cbor");
        }

        @Override
        public CompletableFuture<List<Multihash>> putRaw(PublicKeyHash writer, List<byte[]> blocks) {
            return put(writer, blocks, "raw");
        }

        private CompletableFuture<List<Multihash>> put(PublicKeyHash writer, List<byte[]> blocks, String format) {
            return poster.postMultipart(apiPrefix + "block/put?format=" + format
                    + "&writer=" + encode(writer.toString()), blocks)
                    .thenApply(bytes -> JSONParser.parseStream(new String(bytes))
                            .stream()
                            .map(json -> getObjectHash(json))
                            .collect(Collectors.toList()));
        }

        @Override
        public CompletableFuture<Optional<CborObject>> get(Multihash hash) {
            return poster.get(apiPrefix + "block/get?stream-channels=true&arg=" + hash.toString())
                    .thenApply(raw -> raw.length == 0 ? Optional.empty() : Optional.of(CborObject.fromByteArray(raw)));
        }

        @Override
        public CompletableFuture<Optional<byte[]>> getRaw(Multihash hash) {
            return poster.get(apiPrefix + "block/get?stream-channels=true&arg=" + hash.toString())
                    .thenApply(raw -> raw.length == 0 ? Optional.empty() : Optional.of(raw));
        }

        @Override
        public CompletableFuture<List<Multihash>> recursivePin(Multihash hash) {
            return poster.get(apiPrefix + "pin/add?stream-channels=true&arg=" + hash.toString())
                    .thenApply(this::getPins);
        }

        @Override
        public CompletableFuture<List<Multihash>> recursiveUnpin(Multihash hash) {
            return poster.get(apiPrefix + "pin/rm?stream-channels=true&r=true&arg=" + hash.toString())
                    .thenApply(this::getPins);
        }

        @Override
        public CompletableFuture<List<MultiAddress>> pinUpdate(Multihash existing, Multihash updated) {
            return poster.get(apiPrefix + "pin/update?stream-channels=true&arg=" + existing.toString() + "&arg=" + updated + "&unpin=false")
                    .thenApply(this::getMultiAddr);
        }

        private List<Multihash> getPins(byte[] raw) {
            Map res = (Map)JSONParser.parse(new String(raw));
            List<String> pins = (List<String>)res.get("Pins");
            return pins.stream().map(Cid::decode).collect(Collectors.toList());
        }

        private List<MultiAddress> getMultiAddr(byte[] raw) {
            Map res = (Map)JSONParser.parse(new String(raw));
            List<String> pins = (List<String>)res.get("Pins");
            return pins.stream().map(MultiAddress::new).collect(Collectors.toList());
        }

        @Override
        public CompletableFuture<List<Multihash>> getLinks(Multihash block) {
            return poster.get(apiPrefix + "refs?arg=" + block.toString())
                    .thenApply(raw -> JSONParser.parseStream(new String(raw))
                            .stream()
                            .map(obj -> (String) (((Map) obj).get("Ref")))
                            .map(Cid::decode)
                            .collect(Collectors.toList()));
        }

        @Override
        public CompletableFuture<Optional<Integer>> getSize(Multihash block) {
            return poster.get(apiPrefix + "block/stat?stream-channels=true&arg=" + block.toString())
                    .thenApply(raw -> Optional.of((Integer)((Map)JSONParser.parse(new String(raw))).get("Size")));
        }
    }
}
