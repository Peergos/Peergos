package peergos.server.storage;

import peergos.shared.crypto.hash.*;
import peergos.shared.util.*;

import java.util.*;

public class RamLinkRetrievalCounter implements LinkRetrievalCounter {

    private final Map<Pair<PublicKeyHash, Long>, Long> counts = new HashMap<>();

    @Override
    public synchronized void increment(PublicKeyHash owner, long label) {
        Pair<PublicKeyHash, Long> key = new Pair<>(owner, label);
        counts.putIfAbsent(key, 0L);
        long current = counts.get(key);
        counts.put(key, current+1);
    }

    @Override
    public long getCount(PublicKeyHash owner, long label) {
        return counts.getOrDefault(new Pair<>(owner, label), 0L);
    }
}
