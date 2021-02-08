package peergos.shared.user;

import peergos.shared.cbor.*;

import java.util.*;
import java.util.stream.*;

public class PendingSocialState implements Cborable {

    public final Set<String> pendingOutgoingFollowRequests;

    public PendingSocialState(Set<String> pendingOutgoingFollowRequests) {
        this.pendingOutgoingFollowRequests = pendingOutgoingFollowRequests;
    }

    public PendingSocialState withPending(String username) {
        HashSet<String> updated = new HashSet<>(pendingOutgoingFollowRequests);
        updated.add(username);
        return new PendingSocialState(updated);
    }

    public PendingSocialState withoutPending(String username) {
        HashSet<String> updated = new HashSet<>(pendingOutgoingFollowRequests);
        updated.remove(username);
        return new PendingSocialState(updated);
    }

    public static PendingSocialState empty() {
        return new PendingSocialState(Collections.emptySet());
    }

    @Override
    public CborObject toCbor() {
        Map<String, Cborable> cbor = new TreeMap<>();
        cbor.put("pendingOutgoing", new CborObject.CborList(pendingOutgoingFollowRequests.stream()
                .map(CborObject.CborString::new)
                .collect(Collectors.toList())));
        return CborObject.CborMap.build(cbor);
    }

    public static PendingSocialState fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for PendingSocialState!");
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        List<String> pendingOutgoing = m.getList("pendingOutgoing", c -> ((CborObject.CborString)c).value);
        return new PendingSocialState(new HashSet<>(pendingOutgoing));
    }
}
