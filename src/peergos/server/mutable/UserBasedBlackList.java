package peergos.server.mutable;

import peergos.shared.cbor.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.merklebtree.*;
import peergos.shared.mutable.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

public class UserBasedBlackList implements PublicKeyBlackList {

    private static final long RELOAD_PERIOD = 3_600_000;

    private Map<PublicKeyHash, Boolean> banned = new ConcurrentHashMap<>();
    private final CoreNode core;
    private final MutablePointers mutable;
    private final ContentAddressedStorage dht;
    private final Path source;
    private final ForkJoinPool pool = new ForkJoinPool(1);
    private long lastModified, lastReloaded;

    public UserBasedBlackList(Path source, CoreNode core, MutablePointers mutable, ContentAddressedStorage dht) {
        this.source = source;
        this.core = core;
        this.mutable = mutable;
        this.dht = dht;
        pool.submit(() -> {
            while (true) {
                updateBlackList();
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {}
            }
        });
    }

    private void updateBlackList() {
        long modified = source.toFile().lastModified();
        long now = System.currentTimeMillis();
        if (modified != lastModified || (now - lastReloaded > RELOAD_PERIOD)) {
            System.out.println("Updating blacklist...");
            lastModified = modified;
            lastReloaded = now;
            Set<String> usernames = readUsernameFromFile();
            Set<PublicKeyHash> updated = buildBlackList(usernames);
            banned.clear();
            for (PublicKeyHash hash : updated) {
                banned.put(hash, true);
            }
        }
    }

    private Set<String> readUsernameFromFile() {
        try {
            Stream<String> lines = Files.lines(source);
            Set<String> res = new HashSet<>();
            lines.forEach(name -> res.add(name.trim()));
            return res;
        } catch (IOException e) {
            e.printStackTrace();
            return Collections.emptySet();
        }
    }

    private Set<PublicKeyHash> buildBlackList(Set<String> usernames) {
        Set<PublicKeyHash> res = new HashSet<>();
        for (String username : usernames) {
            res.addAll(buildBlackList(username));
        }
        return res;
    }

    private Set<PublicKeyHash> buildBlackList(String username) {
        try {
            Optional<PublicKeyHash> publicKeyHash = core.getPublicKeyHash(username).get();
            return publicKeyHash
                    .map(this::buildBlackList)
                    .orElseGet(Collections::emptySet);
        } catch (InterruptedException e) {
            return Collections.emptySet();
        } catch (ExecutionException e) {
            throw new RuntimeException(e.getCause());
        }
    }

    private Set<PublicKeyHash> buildBlackList(PublicKeyHash writer) {
        Set<PublicKeyHash> res = new HashSet<>();
        res.add(writer);
        try {
            CommittedWriterData subspaceDescriptor = mutable.getPointer(writer)
                    .thenCompose(dataOpt -> dht.getSigningKey(writer)
                            .thenApply(signer -> dataOpt.isPresent() ?
                                    HashCasPair.fromCbor(CborObject.fromByteArray(signer.get().unsignMessage(dataOpt.get()))).updated :
                                    MaybeMultihash.empty())
                            .thenCompose(x -> getWriterData(writer, x))).get();

            for (PublicKeyHash subKey : subspaceDescriptor.props.ownedKeys) {
                res.addAll(buildBlackList(subKey));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return res;
    }

    private CompletableFuture<CommittedWriterData> getWriterData(PublicKeyHash controller, MaybeMultihash hash) {
        if (!hash.isPresent())
            return CompletableFuture.completedFuture(new CommittedWriterData(MaybeMultihash.empty(), WriterData.createEmpty(controller)));
        return dht.get(hash.get())
                .thenApply(cborOpt -> {
                    if (! cborOpt.isPresent())
                        throw new IllegalStateException("Couldn't retrieve WriterData from dht! " + hash);
                    return new CommittedWriterData(hash, WriterData.fromCbor(cborOpt.get(), null));
                });
    }

    @Override
    public boolean isAllowed(PublicKeyHash keyHash) {
        return ! banned.containsKey(keyHash);
    }
}
