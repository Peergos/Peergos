package peergos.server.storage.auth;

import peergos.shared.storage.auth.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;

public class RamBatCave implements BatCave {

    private final Map<BatId, Bat> bats = new HashMap<>();
    private final Map<String, List<BatWithId>> byUser = new HashMap<>();

    @Override
    public Optional<Bat> getBat(BatId id) {
        return Optional.ofNullable(bats.get(id));
    }

    @Override
    public CompletableFuture<List<BatWithId>> getUserBats(String username, byte[] auth) {
        return Futures.of(byUser.getOrDefault(username, Collections.emptyList()));
    }

    @Override
    public CompletableFuture<Boolean> addBat(String username, BatId id, Bat bat, byte[] auth) {
        if (id.isInline())
            throw new IllegalStateException("Cannot store an inline batId!");
        bats.put(id, bat);
        byUser.putIfAbsent(username, new ArrayList<>());
        byUser.get(username).add(new BatWithId(bat, id.id));
        return Futures.of(true);
    }
}
