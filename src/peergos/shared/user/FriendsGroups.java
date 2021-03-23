package peergos.shared.user;

import peergos.shared.cbor.*;
import peergos.shared.user.fs.*;

import java.util.*;
import java.util.stream.*;

public class FriendsGroups implements Cborable {

    public final Map<String, EntryPoint> pathToGroup;

    public FriendsGroups(Map<String, EntryPoint> pathToGroup) {
        this.pathToGroup = pathToGroup;
    }

    public Set<EntryPoint> getFriends(String friend) {
        return pathToGroup.entrySet().stream()
                .filter(e -> e.getKey().startsWith(friend) || e.getKey().startsWith("/" + friend))
                .map(e -> e.getValue())
                .collect(Collectors.toSet());
    }

    public FriendsGroups addGroup(CapabilityWithPath group, String owner) {
        HashMap<String, EntryPoint> updated = new HashMap<>(pathToGroup);
        updated.put(group.path, new EntryPoint(group.cap, owner));
        return new FriendsGroups(updated);
    }

    public static FriendsGroups empty() {
        return new FriendsGroups(Collections.emptyMap());
    }

    @Override
    public CborObject toCbor() {
        Map<String, Cborable> cbor = new TreeMap<>();

        Map<String, Cborable> groups = pathToGroup.entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue()));
        cbor.put("g", CborObject.CborMap.build(groups));
        return CborObject.CborMap.build(cbor);
    }

    public static FriendsGroups fromCbor(Cborable cbor) {
        if (!(cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor! " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;

        Map<String, EntryPoint> groups = m.getMap("g", c -> ((CborObject.CborString) c).value, EntryPoint::fromCbor);
        return new FriendsGroups(groups);
    }
}
