package peergos.shared.user.fs;

import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.symmetric.SymmetricKey;

import java.util.*;

/** The EncryptedCapability class represents a symmetrically encrypted RelativeCapability
 *
 */
public class EncryptedCapability implements Cborable {
    private final CipherText cipherText;

    public EncryptedCapability(CipherText cipherText) {
        this.cipherText = cipherText;
    }

    public RelativeCapability toCapability(SymmetricKey baseKey) {
        return cipherText.decrypt(baseKey, RelativeCapability::fromCbor, c -> {});
    }

    @Override
    public CborObject toCbor() {
        return cipherText.toCbor();
    }

    public static EncryptedCapability fromCbor(Cborable cbor) {
        return new EncryptedCapability(CipherText.fromCbor(cbor));
    }

    public static EncryptedCapability create(SymmetricKey from, RelativeCapability cap) {
        return new EncryptedCapability(CipherText.build(from, cap));
    }

    public static EncryptedCapability create(SymmetricKey from, SymmetricKey to, Optional<PublicKeyHash> writer, byte[] mapKey) {
        return create(from, new RelativeCapability(writer, mapKey, to, Optional.empty()));
    }
}
