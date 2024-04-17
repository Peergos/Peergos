package peergos.server.storage.auth;

import org.peergos.config.*;
import peergos.shared.io.ipfs.*;

import java.util.*;

public class Want implements Jsonable {

    public final Cid cid;
    public final Optional<String> authHex;
    public Want(Cid cid, Optional<String> authHex) {
        this.cid = cid;
        this.authHex = authHex.flatMap(a -> a.isEmpty() ? Optional.empty() : Optional.of(a));
    }

    public Want(Cid h) {
        this(h, Optional.empty());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Want want = (Want) o;
        return cid.equals(want.cid) && authHex.equals(want.authHex);
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("c", cid.toString());
        authHex.ifPresent(h -> m.put("a", h));
        return m;
    }

    public static Want fromJson(Map<String, String> m) {
        return new Want(Cid.decode(m.get("c")), Optional.ofNullable(m.get("a")));
    }

    @Override
    public int hashCode() {
        return Objects.hash(cid, authHex);
    }

    @Override
    public String toString() {
        return cid.toString() + "(" + authHex.orElse("") + ")";
    }
}
