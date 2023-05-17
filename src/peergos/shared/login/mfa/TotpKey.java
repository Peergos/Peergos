package peergos.shared.login.mfa;

import peergos.shared.io.ipfs.multibase.binary.*;

public class TotpKey {

    public final byte[] key;

    public TotpKey(byte[] key) {
        this.key = key;
    }

    public String encode() {
        return new Base32().encodeToString(key).replaceAll("=","");
    }

    public static TotpKey fromString(String base32) {
        return new TotpKey(new Base32().decode(base32));
    }
}
