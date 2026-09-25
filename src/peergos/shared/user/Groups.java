package peergos.shared.user;

import peergos.shared.cbor.*;
import peergos.shared.crypto.random.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.function.*;

public class Groups implements Cborable {
    public final Map<String, String> uidToGroupName;

    public Groups(Map<String, String> uidToGroupName) {
        this.uidToGroupName = uidToGroupName;
    }

    public static Groups generate(SafeRandom r) {
        Map<String, String> uidToNames = new TreeMap<>();
        uidToNames.put(generateUid(r), SocialState.FRIENDS_GROUP_NAME);
        uidToNames.put(generateUid(r), SocialState.FOLLOWERS_GROUP_NAME);
        return new Groups(uidToNames);
    }

    /* SocialState finds the built-in groups by inverting uid -> name, so a custom group reusing one of
       these names would be returned in its place.
     */
    public static boolean isBuiltInName(String name) {
        return name.equals(SocialState.FRIENDS_GROUP_NAME) || name.equals(SocialState.FOLLOWERS_GROUP_NAME);
    }

    public boolean isBuiltIn(String uid) {
        String name = uidToGroupName.get(uid);
        return name != null && isBuiltInName(name);
    }

    public Groups withGroup(String uid, String name) {
        Map<String, String> updated = new TreeMap<>(uidToGroupName);
        updated.put(uid, name);
        return new Groups(updated);
    }

    public Groups withoutGroup(String uid) {
        Map<String, String> updated = new TreeMap<>(uidToGroupName);
        updated.remove(uid);
        return new Groups(updated);
    }

    /* Generate a uid that cannot clash with a username, but which is a valid filename
     */
    public static String generateUid(SafeRandom r) {
        return "." + ArrayOps.bytesToHex(r.randomBytes(32));
    }

    @Override
    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        SortedMap<String, Cborable> uidToNames = new TreeMap<>();
        for (Map.Entry<String, String> e : uidToGroupName.entrySet()) {
            uidToNames.put(e.getKey(), new CborObject.CborString(e.getValue()));
        }

        state.put("n", CborObject.CborMap.build(uidToNames));
        return CborObject.CborMap.build(state);
    }

    public static Groups fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for Groups!");
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        CborObject.CborMap r = m.get("n", c -> (CborObject.CborMap) c);
        Function<Cborable, String> getString = c -> ((CborObject.CborString) c).value;
        Map<String, String> uidToNames = r.toMap(getString, getString);

        return new Groups(uidToNames);
    }
}
