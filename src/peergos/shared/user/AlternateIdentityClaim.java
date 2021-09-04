package peergos.shared.user;

import jsinterop.annotations.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.io.ipfs.multibase.*;

import java.util.*;

public class AlternateIdentityClaim implements Cborable {

    @JsProperty
    public final String peergosUsername, alternateUsername, alternateService;

    public AlternateIdentityClaim(String peergosUsername, String alternateUsername, String alternateService) {
        this.peergosUsername = peergosUsername;
        this.alternateUsername = alternateUsername;
        this.alternateService = alternateService;
    }

    public String textToPost(String proofFilename) {
        String path = "/public/" + peergosUsername + "/.profile/identities/" + proofFilename + "?open=true";
        return "I am " + peergosUsername + " on Peergos and " + alternateUsername + " on " + alternateService + "\n" +
                "proof: https://beta.peergos.net" + path;
    }

    public static AlternateIdentityClaim parse(String firstLine) {
        firstLine = firstLine.trim();
        String peergosUsername = firstLine.split(" ")[2];
        String remaining = firstLine.substring(peergosUsername.length() + 21);
        String alternativeUsername = remaining.substring(0, remaining.indexOf(" on "));
        String alternateService = remaining.substring(alternativeUsername.length() + 4);
        return new AlternateIdentityClaim(peergosUsername, alternativeUsername, alternateService);
    }

    public static AlternateIdentityClaim decrypt(String encryptedPost, SymmetricKey key, PublicSigningKey identity) {
        CipherText parsed = CipherText.fromCbor(CborObject.fromByteArray(Base58.decode(encryptedPost.trim())));
        byte[] decrypted = parsed.decrypt(key, c -> ((CborObject.CborByteArray) c).value);
        byte[] unsigned = identity.unsignMessage(decrypted);
        return AlternateIdentityClaim.fromCbor(CborObject.fromByteArray(unsigned));
    }

    @Override
    public CborObject toCbor() {
        Map<String, Cborable> cborData = new TreeMap<>();
        cborData.put("pu", new CborObject.CborString(peergosUsername));
        cborData.put("au", new CborObject.CborString(alternateUsername));
        cborData.put("as", new CborObject.CborString(alternateService));
        return CborObject.CborMap.build(cborData);
    }

    public static AlternateIdentityClaim fromCbor(Cborable cbor) {
        if (!(cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for AlternativeIdentityClaim: " + cbor);

        CborObject.CborMap m = (CborObject.CborMap) cbor;
        String peergosUsername = m.getString("pu");
        String alternativeUsername = m.getString("au");
        String alternativeService = m.getString("as");

        return new AlternateIdentityClaim(peergosUsername, alternativeUsername, alternativeService);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AlternateIdentityClaim that = (AlternateIdentityClaim) o;
        return Objects.equals(peergosUsername, that.peergosUsername) &&
                Objects.equals(alternateUsername, that.alternateUsername) &&
                Objects.equals(alternateService, that.alternateService);
    }

    @Override
    public int hashCode() {
        return Objects.hash(peergosUsername, alternateUsername, alternateService);
    }
}
