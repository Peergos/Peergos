package peergos.server.space;

import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;

import java.util.*;
import java.util.stream.*;

public class WriterUsage implements Cborable {
    public final String owner;
    private MaybeMultihash target;
    private long directRetainedStorage;
    private Set<PublicKeyHash> ownedKeys;

    public WriterUsage(String owner, MaybeMultihash target, long directRetainedStorage, Set<PublicKeyHash> ownedKeys) {
        this.owner = owner;
        this.target = target;
        this.directRetainedStorage = directRetainedStorage;
        this.ownedKeys = ownedKeys;
    }

    public synchronized void update(MaybeMultihash target,
                                    Set<PublicKeyHash> removedOwnedKeys,
                                    Set<PublicKeyHash> addedOwnedKeys,
                                    long retainedStorage) {
        this.target = target;
        HashSet<PublicKeyHash> updated = new HashSet<>(ownedKeys);
        updated.removeAll(removedOwnedKeys);
        updated.addAll(addedOwnedKeys);
        this.ownedKeys = Collections.unmodifiableSet(updated);
        this.directRetainedStorage = retainedStorage;
    }

    public MaybeMultihash target() {
        return target;
    }

    public synchronized long directRetainedStorage() {
        return directRetainedStorage;
    }

    public synchronized MaybeMultihash getRoot() {
        return target;
    }

    public synchronized Set<PublicKeyHash> ownedKeys() {
        return Collections.unmodifiableSet(ownedKeys);
    }

    @Override
    public CborObject toCbor() {
        Map<String, Cborable> map = new HashMap<>();
        map.put("owner", new CborObject.CborString(owner));
        map.put("target", target);
        map.put("storage", new CborObject.CborLong(directRetainedStorage));
        map.put("ownedKey", new CborObject.CborList(ownedKeys.stream().collect(Collectors.toList())));
        return CborObject.CborMap.build(map);
    }

    public static WriterUsage fromCbor(Cborable cbor) {
        CborObject.CborMap map = (CborObject.CborMap) cbor;
        String owner = map.getString("owner");
        MaybeMultihash target = map.get("target", MaybeMultihash::fromCbor);
        long storage  = map.getLong("storage");
        List<PublicKeyHash> ownedKeys = map.getList("ownedKey").map(PublicKeyHash::fromCbor);
        return new WriterUsage(owner, target, storage, new HashSet<>(ownedKeys));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        WriterUsage writerUsage = (WriterUsage) o;

        if (directRetainedStorage != writerUsage.directRetainedStorage) return false;
        if (owner != null ? !owner.equals(writerUsage.owner) : writerUsage.owner != null) return false;
        if (target != null ? !target.equals(writerUsage.target) : writerUsage.target != null) return false;
        return ownedKeys != null ? ownedKeys.equals(writerUsage.ownedKeys) : writerUsage.ownedKeys == null;
    }

    @Override
    public int hashCode() {
        int result = owner != null ? owner.hashCode() : 0;
        result = 31 * result + (target != null ? target.hashCode() : 0);
        result = 31 * result + (int) (directRetainedStorage ^ (directRetainedStorage >>> 32));
        result = 31 * result + (ownedKeys != null ? ownedKeys.hashCode() : 0);
        return result;
    }
}
