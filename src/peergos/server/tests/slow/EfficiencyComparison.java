package peergos.server.tests.slow;
import peergos.server.*;
import peergos.server.util.Logging;
import java.util.logging.*;

import peergos.server.storage.*;
import peergos.server.tests.*;
import peergos.shared.*;
import peergos.shared.crypto.*;
import peergos.shared.hamt.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.function.*;

public class EfficiencyComparison {
	private static final Logger LOG = Logging.LOG();

    private static final Crypto crypto = Main.initCrypto();

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
    }

    public static void calculateChampOverhead(Map<ByteArrayWrapper, MaybeMultihash> state) throws Exception {
        for (int bitWidth = 2; bitWidth <= 8; bitWidth++) {
            for (int maxCollisions = 1; maxCollisions <= 6; maxCollisions++) {
                RAMStorage champStorage = new RAMStorage();
                SigningPrivateKeyAndPublicHash champUser = ChampTests.createUser(champStorage, crypto);
                Pair<Champ, Multihash> current = new Pair<>(Champ.empty(), champStorage.put(champUser.publicKeyHash,
                        champUser, Champ.empty().serialize(), crypto.hasher,
                        champStorage.startTransaction(champUser.publicKeyHash).get()).get());

                for (Map.Entry<ByteArrayWrapper, MaybeMultihash> e : state.entrySet()) {
                    current = current.left.put(champUser.publicKeyHash, champUser, e.getKey(), e.getKey().data, 0, MaybeMultihash.empty(),
                            e.getValue(), bitWidth, maxCollisions, x -> x.data,
                            champStorage.startTransaction(champUser.publicKeyHash).get(), champStorage, crypto.hasher,
                            current.right).get();
                }

                int champSize = champStorage.totalSize();
                long champUsage = champStorage.getRecursiveBlockSize(current.right).get();

                int idealUsage = state.size() * (32 + 34);
                LOG.info(bitWidth + "-bit champ, " + maxCollisions + " max-collisions");
                LOG.info("Champ used size: " + champSize + ", Champ usage after gc: " + champUsage + ", ideal: "
                        + idealUsage + ", champ overhead: " + (double) (champUsage * 100 / idealUsage) / 100);
            }
        }
    }
}
