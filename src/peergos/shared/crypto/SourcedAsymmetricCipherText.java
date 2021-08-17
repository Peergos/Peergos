package peergos.shared.crypto;

import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.*;

import java.util.*;
import java.util.function.*;

public class SourcedAsymmetricCipherText implements Cborable {
    public final PublicBoxingKey from;
    public final AsymmetricCipherText cipherText;

    public SourcedAsymmetricCipherText(PublicBoxingKey from, AsymmetricCipherText cipherText) {
        this.from = from;
        this.cipherText = cipherText;
    }

    public static <T extends Cborable> SourcedAsymmetricCipherText build(BoxingKeyPair from, PublicBoxingKey to, T secret) {
        byte[] cipherText = to.encryptMessageFor(secret.serialize(), from.secretBoxingKey);
        return new SourcedAsymmetricCipherText(from.publicBoxingKey, new AsymmetricCipherText(cipherText));
    }

    public <T> T decrypt(SecretBoxingKey to, Function<Cborable, T> fromCbor) {
        return cipherText.decrypt(to, from, fromCbor);
    }

    @Override
    public CborObject toCbor() {
        Map<String, Cborable> result = new TreeMap<>();
        result.put("k", from);
        result.put("c", cipherText);
        return CborObject.CborMap.build(result);
    }

    public static SourcedAsymmetricCipherText fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for SourcedAsymmetricCipherText: "+  cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        PublicBoxingKey source = m.get("k", PublicBoxingKey::fromCbor);
        AsymmetricCipherText cipherText = m.get("c", AsymmetricCipherText::fromCbor);
        return new SourcedAsymmetricCipherText(source, cipherText);
    }
}
