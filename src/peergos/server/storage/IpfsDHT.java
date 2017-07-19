package peergos.server.storage;

import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.multiaddr.MultiAddress;
import peergos.shared.io.ipfs.multihash.Multihash;
import peergos.shared.storage.ContentAddressedStorage;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

public class IpfsDHT implements ContentAddressedStorage {
    private final IPFS ipfs;

    public IpfsDHT(IPFS ipfs) {
        this.ipfs = ipfs;
        try {
            // test connectivity
            ipfs.object._new(Optional.empty());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public IpfsDHT() {
        this(new IPFS(new MultiAddress("/ip4/127.0.0.1/tcp/5001")));
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
        try {
            return CompletableFuture.completedFuture(ipfs.block.put(blocks, Optional.of(format)))
                    .thenApply(nodes -> nodes.stream().map(n -> n.hash).collect(Collectors.toList()));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(Multihash hash) {
        try {
            byte[] raw = ipfs.block.get(hash);
            return CompletableFuture.completedFuture(Optional.of(CborObject.fromByteArray(raw)));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(Multihash hash) {
        try {
            byte[] raw = ipfs.block.get(hash);
            return CompletableFuture.completedFuture(Optional.of(raw));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public CompletableFuture<List<MultiAddress>> pinUpdate(Multihash existing, Multihash updated) {
        try {
            List<MultiAddress> added = ipfs.pin.update(existing, updated, false);
            return CompletableFuture.completedFuture(added);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public CompletableFuture<List<Multihash>> recursivePin(Multihash h) {
        try {
            List<Multihash> added = ipfs.pin.add(h);
            return CompletableFuture.completedFuture(added);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public CompletableFuture<List<Multihash>> recursiveUnpin(Multihash h) {
        try {
            List<Multihash> removed = ipfs.pin.rm(h, true);
            return CompletableFuture.completedFuture(removed);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public CompletableFuture<List<Multihash>> getLinks(Multihash root) {
        CompletableFuture<List<Multihash>> res = new CompletableFuture<>();
        try {
            res.complete(ipfs.refs(root, false));
        } catch (IOException e) {
            res.completeExceptionally(e);
        }
        return res;
    }

    @Override
    public CompletableFuture<Optional<Integer>> getSize(Multihash block) {
        CompletableFuture<Optional<Integer>> res = new CompletableFuture<>();
        try {
            res.complete(Optional.of((Integer)ipfs.block.stat(block).get("Size")));
        } catch (IOException e) {
            res.completeExceptionally(e);
        }
        return res;
    }
}
