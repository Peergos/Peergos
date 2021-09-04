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

public class IdentityLinkProof implements Cborable {
    public static final String SIG_PREFIX = "\nsig: ";

    @JsProperty
    public final IdentityLink claim;
    public final byte[] signature;
    // This allows us to post proofs to other services that reveal nothing to someone without this key
    public final Optional<SymmetricKey> encryptionKey;
    @JsProperty
    public final Optional<String> alternateUrl;

    public IdentityLinkProof(IdentityLink claim,
                             byte[] signature,
                             Optional<SymmetricKey> encryptionKey,
                             Optional<String> alternateUrl) {
        this.claim = claim;
        this.signature = signature;
        this.encryptionKey = encryptionKey;
        this.alternateUrl = alternateUrl;
    }

    @JsMethod
    public boolean hasUrl() {
        return alternateUrl.isPresent();
    }

    public byte[] signedClaim() {
        byte[] body = claim.serialize();
        return ArrayOps.concat(signature, body);
    }

    public boolean isValid(PublicSigningKey peergosIdentity) {
        byte[] unsigned = peergosIdentity.unsignMessage(signedClaim());
        IdentityLink signedClaim = IdentityLink.fromCbor(CborObject.fromByteArray(unsigned));
        if (! signedClaim.equals(claim))
            throw new IllegalStateException("Signature invalid!");
        return true;
    }

    @JsMethod
    public String encodedSignature() {
        return Base58.encode(signature);
    }

    public String alternatePostText(String proofFilename) {
        return claim.textToPost(proofFilename) + SIG_PREFIX + encodedSignature();
    }

    public String encryptedPostText() {
        if (encryptionKey.isEmpty())
            throw new IllegalStateException("No encryption key present on Identity proof!");

        SymmetricKey key = encryptionKey.get();
        CipherText encrypted = CipherText.build(key, new CborObject.CborByteArray(signedClaim()));
        return Base58.encode(encrypted.serialize());
    }

    public IdentityLinkProof withAlternateUrl(String alternateUrl) {
        return new IdentityLinkProof(claim, signature, encryptionKey, Optional.of(alternateUrl));
    }

    public IdentityLinkProof withKey(SymmetricKey key) {
        return new IdentityLinkProof(claim, signature, Optional.of(key), alternateUrl);
    }

    @Override
    public CborObject toCbor() {
        Map<String, Cborable> cborData = new TreeMap<>();
        cborData.put("c", claim);
        cborData.put("s", new CborObject.CborByteArray(signature));
        encryptionKey.ifPresent(k -> cborData.put("k", k));
        alternateUrl.ifPresent(ap -> cborData.put("ap", new CborObject.CborString(ap)));

        List<CborObject> contents = new ArrayList<>();
        contents.add(new CborObject.CborLong(MimeTypes.CBOR_PEERGOS_IDENTITY_PROOF_INT));
        contents.add(CborObject.CborMap.build(cborData));

        return new CborObject.CborList(contents);
    }

    @JsMethod
    public static IdentityLinkProof fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborList))
            throw new IllegalStateException("Invalid cbor for TodoList: " + cbor);

        List<? extends Cborable> contents = ((CborObject.CborList) cbor).value;
        long mimeType = ((CborObject.CborLong) contents.get(0)).value;
        if (mimeType != MimeTypes.CBOR_PEERGOS_IDENTITY_PROOF_INT)
            throw new IllegalStateException("Invalid mimetype for AlternativeIdentityProof: " + mimeType);

        CborObject.CborMap m = (CborObject.CborMap) contents.get(1);
        IdentityLink claim = m.get("c", IdentityLink::fromCbor);
        Optional<SymmetricKey> encryptionKey = m.getOptional("k", SymmetricKey::fromCbor);
        byte[] signature = m.getByteArray("s");
        Optional<String> alternativeUrl = m.getOptional("ap", c -> ((CborObject.CborString) c).value);

        return new IdentityLinkProof(claim, signature, encryptionKey, alternativeUrl);
    }

    @JsMethod
    public static IdentityLinkProof parse(String postContents) {
        String line1 = postContents.trim().split("\n")[0];
        IdentityLink claim = IdentityLink.parse(line1);
        String signatureText = postContents.substring(postContents.indexOf(SIG_PREFIX) + SIG_PREFIX.length()).trim();
        byte[] signature = Base58.decode(signatureText);
        return new IdentityLinkProof(claim, signature, Optional.empty(), Optional.empty());
    }

    @JsMethod
    public static IdentityLinkProof buildAndSign(SigningPrivateKeyAndPublicHash signer,
                                                 String peergosUsername,
                                                 String alternateUsername,
                                                 String alternateService) {
        IdentityLink.IdentityService serviceA = new IdentityLink.IdentityService(Either.a(IdentityLink.KnownService.Peergos));
        IdentityLink.IdentityService serviceB = IdentityLink.IdentityService.parse(alternateService);
        IdentityLink claim = new IdentityLink(peergosUsername, serviceA, alternateUsername, serviceB);
        byte[] signature = signer.secret.signatureOnly(claim.serialize());
        return new IdentityLinkProof(claim, signature, Optional.empty(), Optional.empty());
    }
}
