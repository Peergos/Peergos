package peergos.server.tests.slow;

import peergos.server.storage.*;
import peergos.server.tests.*;
import peergos.shared.*;
import peergos.shared.crypto.*;
import peergos.shared.hamt.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.merklebtree.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.function.*;

public class EfficiencyComparison {

    private static final Crypto crypto = Crypto.initJava();

    public static void main(String[] a) throws Exception {
        Random r = new Random(28);

        Supplier<Multihash> randomHash = () -> {
            byte[] hash = new byte[32];
            r.nextBytes(hash);
            return new Multihash(Multihash.Type.sha2_256, hash);
        };

        Map<ByteArrayWrapper, MaybeMultihash> state = new HashMap<>();

        // build a random tree and keep track of the state
        int nKeys = 10000;
        for (int i = 0; i < nKeys; i++) {
            ByteArrayWrapper key = new ByteArrayWrapper(randomHash.get().getHash());
            Multihash value = randomHash.get();
            state.put(key, MaybeMultihash.of(value));
        }
        calculateChampOverhead(state);
//        calculateBtreeOverhead(state);
    }

    public static void calculateChampOverhead(Map<ByteArrayWrapper, MaybeMultihash> state) throws Exception {
        for (int bitWidth = 2; bitWidth <= 8; bitWidth++) {
            for (int maxCollisions = 1; maxCollisions <= 6; maxCollisions++) {
                RAMStorage champStorage = new RAMStorage();
                SigningPrivateKeyAndPublicHash champUser = ChampTests.createUser(champStorage, crypto);
                Pair<Champ, Multihash> current = new Pair<>(Champ.empty(), champStorage.put(champUser, Champ.empty().serialize()).get());

                for (Map.Entry<ByteArrayWrapper, MaybeMultihash> e : state.entrySet()) {
                    current = current.left.put(champUser, e.getKey(), 0, MaybeMultihash.empty(),
                            e.getValue(), bitWidth, maxCollisions, champStorage, current.right).get();
                }

                int champSize = champStorage.totalSize();
                long champUsage = champStorage.getRecursiveBlockSize(current.right).get();

                int idealUsage = state.size() * (32 + 34);
                System.out.println(bitWidth + "-bit champ, " + maxCollisions + " max-collisions");
                System.out.println("Champ used size: " + champSize + ", Champ usage after gc: " + champUsage + ", ideal: "
                        + idealUsage + ", champ overhead: " + (double) (champUsage * 100 / idealUsage) / 100);
            }
            System.out.println();
        }
    }

    private static void calculateBtreeOverhead(Map<ByteArrayWrapper, MaybeMultihash> state) throws Exception {
        RAMStorage btreeStorage = new RAMStorage();
        SigningPrivateKeyAndPublicHash btreeUser = ChampTests.createUser(btreeStorage, crypto);
        MerkleBTree btree = MerkleBTree.create(btreeUser, btreeStorage).get();
        for (Map.Entry<ByteArrayWrapper, MaybeMultihash> e : state.entrySet()) {
            btree.put(btreeUser, e.getKey().data, MaybeMultihash.empty(), e.getValue().get()).get();
        }
        int btreeSize = btreeStorage.totalSize();
        long btreeUsage = btreeStorage.getRecursiveBlockSize(btree.root.hash.get()).get();
        int idealUsage = state.size() * (32 + 34);
        System.out.println("Btree used size: " + btreeSize + ", Btree usage after gc: " + btreeUsage + ", ideal: "
                + idealUsage + ", btree overhead: " + (double)(btreeUsage * 100 / idealUsage)/100);
    }
}
