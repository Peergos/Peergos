package peergos.server.storage;

import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.io.ipfs.multiaddr.MultiAddress;
import peergos.shared.io.ipfs.multibase.*;
import peergos.shared.io.ipfs.multihash.Multihash;
import peergos.shared.storage.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

public class IpfsDHT implements ContentAddressedStorage {
    private final IPFS ipfs;

    public IpfsDHT(IPFS ipfs) {
        this.ipfs = ipfs;
        try {
            // test connectivity
            id().get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public IpfsDHT() {
        this(new IPFS(new MultiAddress("/ip4/127.0.0.1/tcp/5001")));
    }

    public IpfsDHT(MultiAddress ipfsAddress) {
        this(new IPFS(ipfsAddress));
    }

    @Override
    public ContentAddressedStorage directToOrigin() {
        return this;
    }

    @Override
    public CompletableFuture<Multihash> id() {
        CompletableFuture<Multihash> res = new CompletableFuture<>();
        try {
            Map id = ipfs.id();
            String peerId = (String)id.get("ID");
            res.complete(Cid.decodePeerId(peerId));
        } catch (Exception e) {
            res.completeExceptionally(e);
        }
        return res;
    }

    @Override
    public CompletableFuture<TransactionId> startTransaction(PublicKeyHash owner) {
        // TODO Implement once IPFS has
        return CompletableFuture.completedFuture(new TransactionId(Long.toString(System.currentTimeMillis())));
    }

    @Override
    public CompletableFuture<Boolean> closeTransaction(PublicKeyHash owner, TransactionId tid) {
        // TODO Implement once IPFS has
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public CompletableFuture<List<byte[]>> getChampLookup(PublicKeyHash owner, Multihash root, byte[] champKey) {
        throw new IllegalStateException("Unsupported operation!");
    }

    @Override
    public CompletableFuture<Boolean> gc() {
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public CompletableFuture<List<Multihash>> put(PublicKeyHash owner,
                                                  PublicKeyHash writer,
                                                  List<byte[]> signedHashes,
                                                  List<byte[]> blocks,
                                                  TransactionId tid) {
        return put(writer, signedHashes, blocks, "cbor", tid);
    }

    @Override
    public CompletableFuture<List<Multihash>> putRaw(PublicKeyHash owner,
                                                     PublicKeyHash writer,
                                                     List<byte[]> signatures,
                                                     List<byte[]> blocks,
                                                     TransactionId tid,
                                                     ProgressConsumer<Long> progressConsumer) {
        return put(writer, signatures, blocks, "raw", tid);
    }

    private CompletableFuture<List<Multihash>> put(PublicKeyHash writer,
                                                   List<byte[]> signatures,
                                                   List<byte[]> blocks,
                                                   String format,
                                                   TransactionId tid) {
        CompletableFuture<List<Multihash>> res = new CompletableFuture<>();
        try {
            res.complete(ipfs.block.put(blocks, Optional.of(format))
                    .stream()
                    .map(n -> n.hash)
                    .collect(Collectors.toList()));
        } catch (Exception e) {
            res.completeExceptionally(e);
        }
        return res;
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(Multihash hash) {
        CompletableFuture<Optional<CborObject>> res = new CompletableFuture<>();
        try {
            byte[] raw = ipfs.block.get(hash);
            res.complete(Optional.of(CborObject.fromByteArray(raw)));
        } catch (Exception e) {
            res.completeExceptionally(e);
        }
        return res;
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(Multihash hash) {
        CompletableFuture<Optional<byte[]>> res = new CompletableFuture<>();
        try {
            byte[] raw = ipfs.block.get(hash);
            res.complete(Optional.of(raw));
        } catch (Exception e) {
            res.completeExceptionally(e);
        }
        return res;
    }

    @Override
    public CompletableFuture<List<Multihash>> pinUpdate(PublicKeyHash owner, Multihash existing, Multihash updated) {
        CompletableFuture<List<Multihash>> res = new CompletableFuture<>();
        try {
            List<Multihash> added = ipfs.pin.update(existing, updated, false);
            res.complete(added);
        } catch (Exception e) {
            res.completeExceptionally(e);
        }
        return res;
    }

    @Override
    public CompletableFuture<List<Multihash>> recursivePin(PublicKeyHash owner, Multihash h) {
        CompletableFuture<List<Multihash>> res = new CompletableFuture<>();
        try {
            List<Multihash> added = ipfs.pin.add(h);
            res.complete(added);
        } catch (Exception e) {
            res.completeExceptionally(e);
        }
        return res;
    }

    @Override
    public CompletableFuture<List<Multihash>> recursiveUnpin(PublicKeyHash owner, Multihash h) {
        CompletableFuture<List<Multihash>> res = new CompletableFuture<>();
        try {
            List<Multihash> removed = ipfs.pin.rm(h, true);
            res.complete(removed);
        } catch (Exception e) {
            res.completeExceptionally(e);
        }
        return res;
    }

    @Override
    public CompletableFuture<List<Multihash>> getLinks(Multihash root) {
        CompletableFuture<List<Multihash>> res = new CompletableFuture<>();
        try {
            res.complete(ipfs.refs(root, false));
        } catch (Exception e) {
            res.completeExceptionally(e);
        }
        return res;
    }

    @Override
    public CompletableFuture<Optional<Integer>> getSize(Multihash block) {
        CompletableFuture<Optional<Integer>> res = new CompletableFuture<>();
        try {
            res.complete(Optional.of((Integer)ipfs.block.stat(block).get("Size")));
        } catch (Exception e) {
            res.completeExceptionally(e);
        }
        return res;
    }
}
