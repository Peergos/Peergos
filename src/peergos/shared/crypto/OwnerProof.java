package peergos.shared.crypto;

import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.storage.*;

import java.util.*;
import java.util.concurrent.*;

public class OwnerProof implements Cborable {
    public final PublicKeyHash ownedKey;
    public final byte[] signedOwner;

    public OwnerProof(PublicKeyHash ownedKey, byte[] signedOwner) {
        this.ownedKey = ownedKey;
        this.signedOwner = signedOwner;
    }

    public CompletableFuture<PublicKeyHash> getAndVerifyOwner(PublicKeyHash owner, ContentAddressedStorage ipfs) {
        return ipfs.getSigningKey(owner, ownedKey)
                .thenCompose(signer -> signer
                        .map(k -> k.unsignMessage(signedOwner)
                                .thenApply(unsigned -> PublicKeyHash.fromCbor(CborObject.fromByteArray(unsigned))))
                        .orElseThrow(() -> new IllegalStateException("Couldn't retrieve owned key: " + ownedKey)));
    }

    public static CompletableFuture<OwnerProof> build(SigningPrivateKeyAndPublicHash ownedKeypair, PublicKeyHash owner) {
        return ownedKeypair.secret.signMessage(owner.serialize())
                .thenApply(signed -> new OwnerProof(ownedKeypair.publicKeyHash, signed));
    }

    @Override
    public CborObject toCbor() {
        Map<String, Cborable> result = new TreeMap<>();
        result.put("o", new CborObject.CborMerkleLink(ownedKey));
        result.put("p", new CborObject.CborByteArray(signedOwner));
        return CborObject.CborMap.build(result);
    }

    public static OwnerProof fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Incorrect cbor for OwnerProof: " + cbor);

        CborObject.CborMap m = (CborObject.CborMap) cbor;
        PublicKeyHash ownedKey = m.get("o", PublicKeyHash::fromCbor);
        byte[] proof = m.get("p", c -> (CborObject.CborByteArray) c).value;
        return new OwnerProof(ownedKey, proof);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        OwnerProof that = (OwnerProof) o;
        return Objects.equals(ownedKey, that.ownedKey) &&
                Arrays.equals(signedOwner, that.signedOwner);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(ownedKey);
        result = 31 * result + Arrays.hashCode(signedOwner);
        return result;
    }
}
