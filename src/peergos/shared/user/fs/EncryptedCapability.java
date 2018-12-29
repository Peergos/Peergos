package peergos.shared.user.fs;

import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.symmetric.SymmetricKey;

import java.util.*;

public class EncryptedCapability implements Cborable {
    private final CipherText cipherText;

    public EncryptedCapability(CipherText cipherText) {
        this.cipherText = cipherText;
    }

    public Capability toCapability(SymmetricKey baseKey) {
        return cipherText.decrypt(baseKey, Capability::fromCbor);
    }

    @Override
    public CborObject toCbor() {
        return cipherText.toCbor();
    }

    public static EncryptedCapability fromCbor(Cborable cbor) {
        return new EncryptedCapability(CipherText.fromCbor(cbor));
    }

    public static EncryptedCapability create(SymmetricKey from, Capability cap) {
        return new EncryptedCapability(CipherText.build(from, cap));
    }

    public static EncryptedCapability create(SymmetricKey from, SymmetricKey to, Optional<PublicKeyHash> writer, byte[] mapKey) {
        return create(from, new Capability(writer, mapKey, to, Optional.empty()));
    }
}
