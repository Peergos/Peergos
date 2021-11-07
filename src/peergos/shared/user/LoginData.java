package peergos.shared.user;

import peergos.shared.cbor.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.util.*;

import java.util.*;

public class LoginData implements Cborable {

    public final String username;
    public final UserStaticData entryPoints;
    public final PublicSigningKey authorisedReader;
    public final Optional<Pair<OpLog.BlockWrite, OpLog.PointerWrite>> identityUpdate;

    public LoginData(String username,
                     UserStaticData entryPoints,
                     PublicSigningKey authorisedReader,
                     Optional<Pair<OpLog.BlockWrite, OpLog.PointerWrite>> identityUpdate) {
        this.username = username;
        this.entryPoints = entryPoints;
        this.authorisedReader = authorisedReader;
        this.identityUpdate = identityUpdate;
    }

    @Override
    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("u", new CborObject.CborString(username));
        state.put("e", entryPoints);
        state.put("r", authorisedReader);
        identityUpdate.ifPresent(p -> {
            state.put("b", p.left);
            state.put("p", p.right);
        });
        return CborObject.CborMap.build(state);
    }

    public static LoginData fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for LoginData!");
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        String username = m.getString("u");
        UserStaticData entry = m.get("e", UserStaticData::fromCbor);
        PublicSigningKey authorisedReader = m.get("r", PublicSigningKey::fromCbor);
        Optional<Pair<OpLog.BlockWrite, OpLog.PointerWrite>> identityUpdate = m.containsKey("b") && m.containsKey("p") ?
                Optional.of(new Pair<>(m.get("b", OpLog.BlockWrite::fromCbor), m.get("p", OpLog.PointerWrite::fromCbor))) :
                Optional.empty();
        return new LoginData(username, entry, authorisedReader, identityUpdate);
    }
}
