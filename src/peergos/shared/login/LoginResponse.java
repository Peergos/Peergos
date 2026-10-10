package peergos.shared.login;

import peergos.shared.cbor.*;
import peergos.shared.login.mfa.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.stream.*;

public class LoginResponse implements Cborable {

    public final Either<UserStaticData, MultiFactorAuthRequest> resp;
    /** Present after a successful login, for the device to send with its next one. */
    public final Optional<String> deviceToken;

    public LoginResponse(Either<UserStaticData, MultiFactorAuthRequest> resp, Optional<String> deviceToken) {
        this.resp = resp;
        this.deviceToken = deviceToken;
    }

    public LoginResponse(Either<UserStaticData, MultiFactorAuthRequest> resp) {
        this(resp, Optional.empty());
    }

    public LoginResponse withoutDeviceToken() {
        return new LoginResponse(resp);
    }

    @Override
    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("a", new CborObject.CborBoolean(resp.isA()));
        state.put("r", resp.map(Cborable::toCbor, MultiFactorAuthRequest::toCbor));
        deviceToken.ifPresent(t -> state.put("d", new CborObject.CborString(t)));
        return CborObject.CborMap.build(state);
    }

    public static LoginResponse fromCbor(Cborable cbor) {
        if (!(cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for LoginResponse! " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        boolean isA = m.getBoolean("a");
        Optional<String> deviceToken = m.getOptional("d", c -> ((CborObject.CborString) c).value);
        if (isA)
            return new LoginResponse(Either.a(m.get("r", UserStaticData::fromCbor)), deviceToken);
        return new LoginResponse(Either.b(m.get("r", MultiFactorAuthRequest::fromCbor)), deviceToken);
    }
}
