package peergos.shared.storage;

import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.api.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.io.ipfs.multiaddr.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

public interface ContentAddressedStorageProxy {

    CompletableFuture<TransactionId> startTransaction(Multihash targetServerId, PublicKeyHash owner);

    CompletableFuture<Boolean> closeTransaction(Multihash targetServerId, PublicKeyHash owner, TransactionId tid);

    CompletableFuture<List<byte[]>> getChampLookup(Multihash targetServerId, PublicKeyHash owner, Multihash root, byte[] champKey);

    CompletableFuture<List<Cid>> put(Multihash targetServerId,
                                     PublicKeyHash owner,
                                     PublicKeyHash writer,
                                     List<byte[]> signatures,
                                     List<byte[]> blocks,
                                     TransactionId tid);

    CompletableFuture<List<Cid>> putRaw(Multihash targetServerId,
                                        PublicKeyHash owner,
                                        PublicKeyHash writer,
                                        List<byte[]> signatures,
                                        List<byte[]> blocks,
                                        TransactionId tid,
                                        ProgressConsumer<Long> progressConsumer);

    class HTTP implements ContentAddressedStorageProxy {
        private static final String P2P_PROXY_PROTOCOL = "/http";

        private final HttpPoster poster;
        private final String apiPrefix = "api/v0/";

        public HTTP(HttpPoster poster) {
            this.poster = poster;
        }

        private static Cid getObjectHash(Object rawJson) {
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

        private static String getProxyUrlPrefix(Multihash targetId) {
            return "/p2p/" + targetId.toString() + P2P_PROXY_PROTOCOL + "/";
        }

        @Override
        public CompletableFuture<TransactionId> startTransaction(Multihash targetServerId,
                                                                 PublicKeyHash owner) {
            return poster.get(getProxyUrlPrefix(targetServerId) + apiPrefix
                    + "transaction/start" + "?owner=" + encode(owner.toString()))
                    .thenApply(raw -> new TransactionId(new String(raw)));
        }

        @Override
        public CompletableFuture<Boolean> closeTransaction(Multihash targetServerId,
                                                           PublicKeyHash owner,
                                                           TransactionId tid) {
            return poster.get(getProxyUrlPrefix(targetServerId) + apiPrefix
                    + "transaction/close?arg=" + tid.toString() + "&owner=" + encode(owner.toString()))
                    .thenApply(raw -> new String(raw).equals("1"));
        }

        @Override
        public CompletableFuture<List<byte[]>> getChampLookup(Multihash targetServerId,
                                                              PublicKeyHash owner,
                                                              Multihash root,
                                                              byte[] champKey) {
            return poster.get(getProxyUrlPrefix(targetServerId) + apiPrefix
                    + "champ/get?arg=" + root.toString() + "&arg=" + ArrayOps.bytesToHex(champKey) + "&owner=" + encode(owner.toString()))
                    .thenApply(CborObject::fromByteArray)
                    .thenApply(c -> (CborObject.CborList)c)
                    .thenApply(res -> res.map(c -> ((CborObject.CborByteArray)c).value));
        }

        @Override
        public CompletableFuture<List<Cid>> put(Multihash targetServerId,
                                                PublicKeyHash owner,
                                                PublicKeyHash writer,
                                                List<byte[]> signatures,
                                                List<byte[]> blocks,
                                                TransactionId tid) {
            return put(targetServerId, owner, writer, signatures, blocks, "cbor", tid);
        }

        @Override
        public CompletableFuture<List<Cid>> putRaw(Multihash targetServerId,
                                                   PublicKeyHash owner,
                                                   PublicKeyHash writer,
                                                   List<byte[]> signatures,
                                                   List<byte[]> blocks,
                                                   TransactionId tid,
                                                   ProgressConsumer<Long> progressConsumer) {
            return put(targetServerId, owner, writer, signatures, blocks, "raw", tid);
        }

        private CompletableFuture<List<Cid>> put(Multihash targetServerId,
                                                 PublicKeyHash owner,
                                                 PublicKeyHash writer,
                                                 List<byte[]> signatures,
                                                 List<byte[]> blocks,
                                                 String format,
                                                 TransactionId tid) {
            return poster.postMultipart(getProxyUrlPrefix(targetServerId) + apiPrefix + "block/put?format=" + format
                    + "&owner=" + encode(owner.toString())
                    + "&transaction=" + encode(tid.toString())
                    + "&writer=" + encode(writer.toString())
                    + "&signatures=" + signatures.stream().map(ArrayOps::bytesToHex).reduce("", (a, b) -> a + "," + b).substring(1), blocks)
                    .thenApply(bytes -> JSONParser.parseStream(new String(bytes))
                            .stream()
                            .map(json -> getObjectHash(json))
                            .collect(Collectors.toList()));
        }
    }
}
