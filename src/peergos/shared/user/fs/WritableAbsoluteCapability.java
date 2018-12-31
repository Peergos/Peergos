package peergos.shared.user.fs;

import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.symmetric.*;

import java.util.*;

public class WritableAbsoluteCapability extends AbsoluteCapability {

    public WritableAbsoluteCapability(PublicKeyHash owner, PublicKeyHash writer, byte[] mapKey, SymmetricKey baseKey, SecretSigningKey signer) {
        super(owner, writer, mapKey, baseKey, Optional.of(signer));
    }

    public SigningPrivateKeyAndPublicHash signer() {
        return new SigningPrivateKeyAndPublicHash(writer, signer.get());
    }
}
