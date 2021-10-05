package peergos.shared.user;

import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.social.*;

import java.util.*;
import java.util.stream.*;

public class UserSnapshot implements Cborable {

    public final Map<PublicKeyHash, byte[]> pointerState;
    public final List<BlindFollowRequest> pendingFollowReqs;
    public final Optional<LoginData> login;

    public UserSnapshot(Map<PublicKeyHash, byte[]> pointerState,
                        List<BlindFollowRequest> pendingFollowReqs,
                        Optional<LoginData> login) {
        this.pointerState = pointerState;
        this.pendingFollowReqs = pendingFollowReqs;
        this.login = login;
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
        login.ifPresent(d -> state.put("l", d));
        return CborObject.CborMap.build(state);
    }

    public static UserSnapshot fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for FileProperties! " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        List<BlindFollowRequest> pendingFollowReqs = m.getList("f", BlindFollowRequest::fromCbor);
        Map<PublicKeyHash, byte[]> pointerState = ((CborObject.CborList)m.get("p"))
                .getMap(PublicKeyHash::fromCbor, c -> ((CborObject.CborByteArray)c).value);
        Optional<LoginData> login = m.getOptional("l", LoginData::fromCbor);
        return new UserSnapshot(pointerState, pendingFollowReqs, login);
    }

    public static UserSnapshot empty() {
        return new UserSnapshot(Collections.emptyMap(), Collections.emptyList(), Optional.empty());
    }
}
