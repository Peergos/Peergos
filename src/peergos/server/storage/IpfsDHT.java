package peergos.server.storage;

import peergos.shared.cbor.*;
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
    public CompletableFuture<Multihash> id() {
        CompletableFuture<Multihash> res = new CompletableFuture<>();
        try {
            Map id = ipfs.id();
            res.complete(Multihash.fromBase58((String)id.get("ID")));
        } catch (Exception e) {
            res.completeExceptionally(e);
        }
        return res;
    }

    @Override
    public CompletableFuture<List<Multihash>> put(PublicKeyHash writer, List<byte[]> signatures, List<byte[]> blocks) {
        return put(writer, signatures, blocks, "cbor");
    }

    @Override
    public CompletableFuture<List<Multihash>> putRaw(PublicKeyHash writer, List<byte[]> signatures, List<byte[]> blocks) {
        return put(writer, signatures, blocks, "raw");
    }

    private CompletableFuture<List<Multihash>> put(PublicKeyHash writer, List<byte[]> signatures, List<byte[]> blocks, String format) {
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
    public CompletableFuture<List<MultiAddress>> pinUpdate(Multihash existing, Multihash updated) {
        CompletableFuture<List<MultiAddress>> res = new CompletableFuture<>();
        try {
            List<MultiAddress> added = ipfs.pin.update(existing, updated, false);
            res.complete(added);
        } catch (Exception e) {
            res.completeExceptionally(e);
        }
        return res;
    }

    @Override
    public CompletableFuture<List<Multihash>> recursivePin(Multihash h) {
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
    public CompletableFuture<List<Multihash>> recursiveUnpin(Multihash h) {
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
