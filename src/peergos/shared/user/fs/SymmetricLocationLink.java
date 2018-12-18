package peergos.shared.user.fs;

import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.symmetric.SymmetricKey;

import java.util.*;

public class SymmetricLocationLink implements Cborable {
    private final CipherText cipherText;

    public SymmetricLocationLink(CipherText cipherText) {
        this.cipherText = cipherText;
    }

    public Capability toCapability(SymmetricKey baseKey) {
        return cipherText.decrypt(baseKey, Capability::fromCbor);
    }

    @Override
    public CborObject toCbor() {
        return cipherText.toCbor();
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
