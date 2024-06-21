package peergos.shared.user;

import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.social.*;
import peergos.shared.storage.*;
import peergos.shared.storage.auth.*;

import java.util.*;
import java.util.stream.*;

public class UserSnapshot implements Cborable {

    public final Map<PublicKeyHash, byte[]> pointerState;
    public final List<BlindFollowRequest> pendingFollowReqs;
    public final List<BatWithId> mirrorBats;
    public final Optional<LoginData> login;
    public final LinkCounts linkCounts;

    public UserSnapshot(Map<PublicKeyHash, byte[]> pointerState,
                        List<BlindFollowRequest> pendingFollowReqs,
                        List<BatWithId> mirrorBats,
                        Optional<LoginData> login,
                        LinkCounts linkCounts) {
        this.pointerState = pointerState;
        this.pendingFollowReqs = pendingFollowReqs;
        this.mirrorBats = mirrorBats;
        this.login = login;
        this.linkCounts = linkCounts;
    }

    @Override
    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("f", new CborObject.CborList(pendingFollowReqs));
        TreeMap<CborObject, Cborable> pointerMap = pointerState.entrySet()
                .stream()
                .collect(Collectors.toMap(
                    e -> e.getKey().toCbor(),
                    e -> new CborObject.CborByteArray(e.getValue()),
                    (a,b) -> a,
                    TreeMap::new
                ));
        state.put("p", new CborObject.CborList(pointerMap));
        state.put("b", new CborObject.CborList(mirrorBats));
        login.ifPresent(d -> state.put("l", d));
        state.put("lc", linkCounts.toCbor());
        return CborObject.CborMap.build(state);
    }

    public static UserSnapshot fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for FileProperties! " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        List<BlindFollowRequest> pendingFollowReqs = m.getList("f", BlindFollowRequest::fromCbor);
        Map<PublicKeyHash, byte[]> pointerState = ((CborObject.CborList)m.get("p"))
                .getMap(PublicKeyHash::fromCbor, c -> ((CborObject.CborByteArray)c).value);
        List<BatWithId> mirrorBats = m.getList("b", BatWithId::fromCbor);
        Optional<LoginData> login = m.getOptional("l", LoginData::fromCbor);
        LinkCounts lc = m.get("lc", LinkCounts::fromCbor);
        return new UserSnapshot(pointerState, pendingFollowReqs, mirrorBats, login, lc);
    }

    public static UserSnapshot empty() {
        return new UserSnapshot(Collections.emptyMap(), Collections.emptyList(), Collections.emptyList(),
                Optional.empty(), new LinkCounts(Collections.emptyMap()));
    }
}
