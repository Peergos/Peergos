package peergos.shared.user.fs;

import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.symmetric.SymmetricKey;
import peergos.shared.util.*;

import java.util.*;

public class SymmetricLocationLink implements Cborable {
    private final CipherText cipherText;

    public SymmetricLocationLink(CipherText cipherText) {
        this.cipherText = cipherText;
    }

    public Location targetLocation(SymmetricKey from) {
        return cipherText.decrypt(from, Capability::fromCbor).location;
    }

    public SymmetricKey target(SymmetricKey from) {
        return cipherText.decrypt(from, Capability::fromCbor).baseKey;
    }

    @Override
    public CborObject toCbor() {
        return cipherText.toCbor();
    }

    public Capability toReadableFilePointer(SymmetricKey baseKey) {
       Location loc =  targetLocation(baseKey);
       SymmetricKey key = target(baseKey);
       return new Capability(loc.owner, loc.writer, loc.getMapKey(), key);
    }

    public static SymmetricLocationLink fromCbor(Cborable cbor) {
        return new SymmetricLocationLink(CipherText.fromCbor(cbor));
    }

    public static SymmetricLocationLink create(SymmetricKey from, Capability cap) {
        return new SymmetricLocationLink(CipherText.build(from, cap));
    }

    public static SymmetricLocationLink create(SymmetricKey from, SymmetricKey to, Location location) {
        return create(from, new Capability(location, Optional.empty(), to));
    }
}
