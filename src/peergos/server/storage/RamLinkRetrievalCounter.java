package peergos.server.storage;

import peergos.shared.storage.*;
import peergos.shared.util.*;

import java.time.*;
import java.util.*;
import java.util.stream.*;

public class RamLinkRetrievalCounter implements LinkRetrievalCounter {

    private final Map<Pair<String, Long>, Pair<Long, LocalDateTime>> counts = new HashMap<>();

    @Override
    public synchronized void increment(String owner, long label) {
        Pair<String, Long> key = new Pair<>(owner, label);
        LocalDateTime now = LocalDateTime.now();
        counts.putIfAbsent(key, new Pair<>(0L, now));
        Pair<Long, LocalDateTime> current = counts.get(key);
        counts.put(key, new Pair<>(current.left+1, now));
    }

    @Override
    public long getCount(String owner, long label) {
        return Optional.ofNullable(counts.get(new Pair<>(owner, label))).map(p -> p.left).orElse(0L);
    }

    @Override
    public Optional<LocalDateTime> getLatestModificationTime(String owner) {
        return counts.entrySet().stream()
                .filter(e -> e.getKey().left.equals(owner))
                .map(e -> e.getValue().right)
                .sorted((a, b) -> -a.compareTo(b))
                .findFirst();
    }

    @Override
    public void setCounts(String owner, LinkCounts counts) {
        counts.counts.forEach((k, v) -> {
            this.counts.put(new Pair<>(owner, k), v);
        });
    }

    @Override
    public LinkCounts getUpdatedCounts(String owner, LocalDateTime after) {
        return new LinkCounts(counts.entrySet()
                .stream()
                .filter(e -> e.getKey().left.equals(owner) && e.getValue().right.isAfter(after))
                .collect(Collectors.toMap(e -> e.getKey().right, Map.Entry::getValue)));
    }
}
