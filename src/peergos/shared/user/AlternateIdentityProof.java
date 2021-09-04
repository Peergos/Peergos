package peergos.shared.user;

import jsinterop.annotations.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.io.ipfs.multibase.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.util.*;

public class AlternateIdentityProof implements Cborable {
    public static final String SIG_PREFIX = "\nsig: ";

    public final AlternateIdentityClaim claim;
    public final byte[] signature;
    // This allows us to post proofs to other services that reveal nothing to someone without this key
    public final Optional<SymmetricKey> encryptionKey;
    public final Optional<String> alternateUrl;

    public AlternateIdentityProof(AlternateIdentityClaim claim,
                                  byte[] signature,
                                  Optional<SymmetricKey> encryptionKey,
                                  Optional<String> alternateUrl) {
        this.claim = claim;
        this.encryptionKey = encryptionKey;
        this.signature = signature;
        this.alternateUrl = alternateUrl;
    }

    public byte[] signedClaim() {
        byte[] body = claim.serialize();
        return ArrayOps.concat(signature, body);
    }

    public boolean isValid(PublicSigningKey peergosIdentity) {
        byte[] unsigned = peergosIdentity.unsignMessage(signedClaim());
        AlternateIdentityClaim signedClaim = AlternateIdentityClaim.fromCbor(CborObject.fromByteArray(unsigned));
        if (! signedClaim.equals(claim))
            throw new IllegalStateException("Signature invalid!");
        return true;
    }

    public String alternatePostText(String proofFilename) {
        return claim.textToPost(proofFilename) + SIG_PREFIX + Base58.encode(signature);
    }

    public String encryptedPostText() {
        if (encryptionKey.isEmpty())
            throw new IllegalStateException("No encryption key present on Identity proof!");

        SymmetricKey key = encryptionKey.get();
        CipherText encrypted = CipherText.build(key, new CborObject.CborByteArray(signedClaim()));
        return Base58.encode(encrypted.serialize());
    }

    public AlternateIdentityProof withAlternateUrl(String alternateUrl) {
        return new AlternateIdentityProof(claim, signature, encryptionKey, Optional.of(alternateUrl));
    }

    public AlternateIdentityProof withKey(SymmetricKey key) {
        return new AlternateIdentityProof(claim, signature, Optional.of(key), alternateUrl);
    }

    @Override
    public CborObject toCbor() {
        Map<String, Cborable> cborData = new TreeMap<>();
        cborData.put("c", claim);
        cborData.put("s", new CborObject.CborByteArray(signature));
        encryptionKey.ifPresent(k -> cborData.put("k", k));
        alternateUrl.ifPresent(ap -> cborData.put("ap", new CborObject.CborString(ap)));

        List<CborObject> contents = new ArrayList<>();
        contents.add(new CborObject.CborLong(MimeTypes.CBOR_PEERGOS_TODO_INT));
        contents.add(CborObject.CborMap.build(cborData));

        return new CborObject.CborList(contents);
    }

    public static AlternateIdentityProof fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborList))
            throw new IllegalStateException("Invalid cbor for TodoList: " + cbor);

        List<? extends Cborable> contents = ((CborObject.CborList) cbor).value;
        long mimeType = ((CborObject.CborLong) contents.get(0)).value;
        if (mimeType != MimeTypes.CBOR_PEERGOS_IDENTITY_PROOF_INT)
            throw new IllegalStateException("Invalid mimetype for AlternativeIdentityProof: " + mimeType);

        CborObject.CborMap m = (CborObject.CborMap) contents.get(1);
        AlternateIdentityClaim claim = m.get("c", AlternateIdentityClaim::fromCbor);
        Optional<SymmetricKey> encryptionKey = m.getOptional("k", SymmetricKey::fromCbor);
        byte[] signature = m.getByteArray("s");
        Optional<String> alternativeUrl = m.getOptional("ap", c -> ((CborObject.CborString) c).value);

        return new AlternateIdentityProof(claim, signature, encryptionKey, alternativeUrl);
    }

    @JsMethod
    public static AlternateIdentityProof parse(String postContents) {
        String line1 = postContents.trim().split("\n")[0];
        AlternateIdentityClaim claim = AlternateIdentityClaim.parse(line1);
        String signatureText = postContents.substring(postContents.indexOf(SIG_PREFIX) + SIG_PREFIX.length()).trim();
        byte[] signature = Base58.decode(signatureText);
        return new AlternateIdentityProof(claim, signature, Optional.empty(), Optional.empty());
    }

    @JsMethod
    public static AlternateIdentityProof buildAndSign(SigningPrivateKeyAndPublicHash signer,
                                                      String peergosUsername,
                                                      String alternateUsername,
                                                      String alternateService) {
        AlternateIdentityClaim claim = new AlternateIdentityClaim(peergosUsername, alternateUsername, alternateService);
        byte[] signature = signer.secret.signatureOnly(claim.serialize());
        return new AlternateIdentityProof(claim, signature, Optional.empty(), Optional.empty());
    }
}
