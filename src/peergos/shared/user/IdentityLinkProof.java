package peergos.shared.user;

import jsinterop.annotations.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.io.ipfs.bases.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;

public class IdentityLinkProof implements Cborable {
    public static final String SIG_PREFIX = "\nsig: ";

    @JsProperty
    public final IdentityLink claim;
    public final byte[] signature;
    // This allows us to post proofs to other services that reveal nothing to someone without this key
    public final Optional<SymmetricKey> encryptionKey;
    @JsProperty
    public final Optional<String> postUrl;

    public IdentityLinkProof(IdentityLink claim,
                             byte[] signature,
                             Optional<SymmetricKey> encryptionKey,
                             Optional<String> postUrl) {
        this.claim = claim;
        this.signature = signature;
        this.encryptionKey = encryptionKey;
        this.postUrl = postUrl;
    }

    @JsMethod
    public boolean hasUrl() {
        return postUrl.isPresent();
    }

    public byte[] signedClaim() {
        byte[] body = claim.serialize();
        return ArrayOps.concat(signature, body);
    }

    public CompletableFuture<Boolean> isValid(PublicSigningKey peergosIdentity) {
        return peergosIdentity.unsignMessage(signedClaim()).thenApply(unsigned -> {
            IdentityLink signedClaim = IdentityLink.fromCbor(CborObject.fromByteArray(unsigned));
            if (!signedClaim.equals(claim))
                throw new IllegalStateException("Signature invalid!");
            return true;
        });
    }

    @JsMethod
    public String encodedSignature() {
        return Base58.encode(signature);
    }

    public String postText(String urlToPeergosPost) {
        return claim.textToPost() + SIG_PREFIX + encodedSignature() + "\nproof: " + urlToPeergosPost;
    }

    public String getFilename() {
        return claim.usernameB + "." + claim.serviceB.name() + ".id.cbor";
    }

    public String getUrlToPost(String peergosServerUrl, FileWrapper proofFile, boolean isPublic) {
        if (peergosServerUrl.endsWith("/"))
            peergosServerUrl = peergosServerUrl.substring(0, peergosServerUrl.length() - 1);
        if (isPublic) {
            String pathToProof = claim.usernameA + "/.profile/ids/" + getFilename();
            String path = "/public/" + pathToProof + "?open=true";
            return peergosServerUrl + path;
        }
        return peergosServerUrl + "/#%7B%22secretLink%22:true%2c%22link%22:%22" + proofFile.toLink() + "%22%2c%22open%22:true%7D";
    }

    public String encryptedPostText() {
        if (encryptionKey.isEmpty())
            throw new IllegalStateException("No encryption key present on Identity proof!");

        SymmetricKey key = encryptionKey.get();
        CipherText encrypted = CipherText.build(key, new CborObject.CborByteArray(signedClaim()));
        return Base58.encode(encrypted.serialize());
    }

    public IdentityLinkProof withPostUrl(String postUrl) {
        return new IdentityLinkProof(claim, signature, encryptionKey, Optional.of(postUrl));
    }

    public IdentityLinkProof withKey(SymmetricKey key) {
        return new IdentityLinkProof(claim, signature, Optional.of(key), postUrl);
    }

    @Override
    public CborObject toCbor() {
        Map<String, Cborable> cborData = new TreeMap<>();
        cborData.put("c", claim);
        cborData.put("s", new CborObject.CborByteArray(signature));
        encryptionKey.ifPresent(k -> cborData.put("k", k));
        postUrl.ifPresent(ap -> cborData.put("bu", new CborObject.CborString(ap)));

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
        Optional<String> alternativeUrl = m.getOptional("bu", c -> ((CborObject.CborString) c).value);

        return new IdentityLinkProof(claim, signature, encryptionKey, alternativeUrl);
    }

    @JsMethod
    public static IdentityLinkProof parse(String postContents) {
        String line1 = postContents.trim().split("\n")[0];
        IdentityLink claim = IdentityLink.parse(line1);
        int signatureStart = postContents.indexOf(SIG_PREFIX) + SIG_PREFIX.length();
        String signatureText = postContents.substring(signatureStart, postContents.indexOf("\n", signatureStart)).trim();
        byte[] signature = Base58.decode(signatureText);
        return new IdentityLinkProof(claim, signature, Optional.empty(), Optional.empty());
    }

    @JsMethod
    public static CompletableFuture<IdentityLinkProof> buildAndSign(SigningPrivateKeyAndPublicHash signer,
                                                                   String peergosUsername,
                                                                   String alternateUsername,
                                                                   String alternateService) {
        IdentityLink.IdentityService serviceA = new IdentityLink.IdentityService(Either.a(IdentityLink.KnownService.Peergos));
        IdentityLink.IdentityService serviceB = IdentityLink.IdentityService.parse(alternateService);
        IdentityLink claim = new IdentityLink(peergosUsername, serviceA, alternateUsername, serviceB);
        return signer.secret.signatureOnly(claim.serialize())
                .thenApply(signature -> new IdentityLinkProof(claim, signature, Optional.empty(), Optional.empty()));
    }
}
