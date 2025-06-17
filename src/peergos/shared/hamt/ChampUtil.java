package peergos.shared.hamt;

import peergos.shared.MaybeMultihash;
import peergos.shared.cbor.Cborable;
import peergos.shared.crypto.SigningPrivateKeyAndPublicHash;
import peergos.shared.crypto.hash.Hasher;
import peergos.shared.crypto.hash.PublicKeyHash;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.storage.ContentAddressedStorage;
import peergos.shared.storage.TransactionId;
import peergos.shared.storage.auth.BatId;
import peergos.shared.util.ByteArrayWrapper;
import peergos.shared.util.Futures;
import peergos.shared.util.Pair;
import peergos.shared.util.Triple;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

public class ChampUtil {

    public static <V extends Cborable> CompletableFuture<Pair<Champ<V>, Multihash>> merge(
            PublicKeyHash owner,
            SigningPrivateKeyAndPublicHash writer,
            MaybeMultihash original,
            MaybeMultihash updated,
            MaybeMultihash remote,
            Optional<BatId> mirrorBat,
            TransactionId tid,
            int bitWidth,
            int maxHashCollisionsPerLevel,
            Function<ByteArrayWrapper, CompletableFuture<byte[]>> keyHasher,
            Function<Cborable, V> fromCbor,
            ContentAddressedStorage storage,
            Hasher writeHasher) {
        Set<ByteArrayWrapper> ourChangedKeys = new HashSet<>();
        Set<ByteArrayWrapper> remoteChangedKeys = new HashSet<>();
        List<Triple<ByteArrayWrapper, Optional<V>, Optional<V>>> remoteUpdates = new ArrayList<>();
        return applyToDiff(owner, original, updated, 0, keyHasher, Collections.emptyList(), Collections.emptyList(),
                    t -> ourChangedKeys.add(t.left), bitWidth, storage, fromCbor)
                .thenCompose(b -> applyToDiff(owner, original, remote, 0, keyHasher, Collections.emptyList(), Collections.emptyList(),
                    t -> {
                        remoteChangedKeys.add(t.left);
                        remoteUpdates.add(t);
                    }, bitWidth, storage, fromCbor))
                .thenCompose(x -> {
                    ourChangedKeys.retainAll(remoteChangedKeys);
                    if (! ourChangedKeys.isEmpty())
                        throw new IllegalStateException("Concurrent modification of a file or directory!");
                    return updated.map(h -> storage.get(owner, (Cid)h, Optional.empty()))
                            .orElseGet(() -> CompletableFuture.completedFuture(Optional.empty()))
                            .thenApply(rawOpt -> rawOpt.map(y -> Champ.fromCbor(y, fromCbor)))
                            .thenCompose(champ -> Futures.reduceAll(remoteUpdates,
                                    new Pair<>(champ.get(), updated.get()),
                                    (p, t) -> keyHasher.apply(t.left)
                                            .thenCompose(hash -> p.left.put(owner, writer, t.left, hash, 0, t.middle, t.right,
                                                    bitWidth, maxHashCollisionsPerLevel,
                                                    mirrorBat, keyHasher, tid, storage, writeHasher, p.right)),
                                    (a, b) -> b));
                });
    }

    public static <V extends Cborable> CompletableFuture<Boolean> applyToDiff(
            PublicKeyHash owner,
            MaybeMultihash original,
            MaybeMultihash updated,
            int depth,
            Function<ByteArrayWrapper, CompletableFuture<byte[]>> hasher,
            List<Champ.KeyElement<V>> higherLeftMappings,
            List<Champ.KeyElement<V>> higherRightMappings,
            Consumer<Triple<ByteArrayWrapper, Optional<V>, Optional<V>>> consumer,
            int bitWidth,
            ContentAddressedStorage storage,
            Function<Cborable, V> fromCbor) {

        if (updated.equals(original))
            return CompletableFuture.completedFuture(true);
        return original.map(h -> storage.get(owner, (Cid)h, Optional.empty())).orElseGet(() -> CompletableFuture.completedFuture(Optional.empty()))
                .thenApply(rawOpt -> rawOpt.map(y -> Champ.fromCbor(y, fromCbor)))
                .thenCompose(left -> updated.map(h -> storage.get(owner, (Cid)h, Optional.empty())).orElseGet(() -> CompletableFuture.completedFuture(Optional.empty()))
                        .thenApply(rawOpt -> rawOpt.map(y -> Champ.fromCbor(y, fromCbor)))
                        .thenCompose(right -> Champ.hashAndMaskKeys(higherLeftMappings, depth, bitWidth, hasher)
                                .thenCompose(leftHigherMappingsByBit -> Champ.hashAndMaskKeys(higherRightMappings, depth, bitWidth, hasher)
                                        .thenCompose(rightHigherMappingsByBit -> {

                                            int leftMax = left.map(c -> Math.max(c.dataMap.length(), c.nodeMap.length())).orElse(0);
                                            int rightMax = right.map(c -> Math.max(c.dataMap.length(), c.nodeMap.length())).orElse(0);
                                            int maxBit = Math.max(leftMax, rightMax);
                                            int leftDataIndex = 0, rightDataIndex = 0, leftNodeCount = 0, rightNodeCount = 0;

                                            List<CompletableFuture<Boolean>> deeperLayers = new ArrayList<>();

                                            for (int i = 0; i < maxBit; i++) {
                                                // either the payload is present OR higher mappings are non empty OR the champ is absent
                                                Optional<Champ.HashPrefixPayload<V>> leftPayload = Champ.getElement(i, leftDataIndex, leftNodeCount, left);
                                                Optional<Champ.HashPrefixPayload<V>> rightPayload = Champ.getElement(i, rightDataIndex, rightNodeCount, right);

                                                List<Champ.KeyElement<V>> leftHigherMappings = leftHigherMappingsByBit.getOrDefault(i, Collections.emptyList());
                                                List<Champ.KeyElement<V>> leftMappings = leftPayload
                                                        .filter(p -> !p.isShard())
                                                        .map(p -> Arrays.asList(p.mappings))
                                                        .orElse(leftHigherMappings);
                                                List<Champ.KeyElement<V>> rightHigherMappings = rightHigherMappingsByBit.getOrDefault(i, Collections.emptyList());
                                                List<Champ.KeyElement<V>> rightMappings = rightPayload
                                                        .filter(p -> !p.isShard())
                                                        .map(p -> Arrays.asList(p.mappings))
                                                        .orElse(rightHigherMappings);

                                                Optional<MaybeMultihash> leftShard = leftPayload
                                                        .filter(p -> p.isShard())
                                                        .map(p -> p.link);

                                                Optional<MaybeMultihash> rightShard = rightPayload
                                                        .filter(p -> p.isShard())
                                                        .map(p -> p.link);

                                                if (leftShard.isPresent() || rightShard.isPresent()) {
                                                    deeperLayers.add(applyToDiff(owner,
                                                            leftShard.orElse(MaybeMultihash.empty()),
                                                            rightShard.orElse(MaybeMultihash.empty()), depth + 1, hasher,
                                                            leftMappings, rightMappings, consumer, bitWidth, storage, fromCbor));
                                                } else {
                                                    Map<ByteArrayWrapper, Optional<V>> leftMap = leftMappings.stream()
                                                            .collect(Collectors.toMap(e -> e.key, e -> e.valueHash));
                                                    Map<ByteArrayWrapper, Optional<V>> rightMap = rightMappings.stream()
                                                            .collect(Collectors.toMap(e -> e.key, e -> e.valueHash));

                                                    HashSet<ByteArrayWrapper> both = new HashSet<>(leftMap.keySet());
                                                    both.retainAll(rightMap.keySet());

                                                    for (Map.Entry<ByteArrayWrapper, Optional<V>> entry : leftMap.entrySet()) {
                                                        if (! both.contains(entry.getKey()))
                                                            consumer.accept(new Triple<>(entry.getKey(), entry.getValue(), Optional.empty()));
                                                        else if (! entry.getValue().equals(rightMap.get(entry.getKey())))
                                                            consumer.accept(new Triple<>(entry.getKey(), entry.getValue(), rightMap.get(entry.getKey())));
                                                    }
                                                    for (Map.Entry<ByteArrayWrapper, Optional<V>> entry : rightMap.entrySet()) {
                                                        if (! both.contains(entry.getKey()))
                                                            consumer.accept(new Triple<>(entry.getKey(), Optional.empty(), entry.getValue()));
                                                    }
                                                }

                                                if (leftPayload.isPresent()) {
                                                    if (leftPayload.get().isShard())
                                                        leftNodeCount++;
                                                    else
                                                        leftDataIndex++;
                                                }
                                                if (rightPayload.isPresent()) {
                                                    if (rightPayload.get().isShard())
                                                        rightNodeCount++;
                                                    else
                                                        rightDataIndex++;
                                                }
                                            }

                                            return Futures.combineAll(deeperLayers).thenApply(x -> true);
                                        })))
                );
    }
}
