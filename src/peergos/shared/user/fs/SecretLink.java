package peergos.shared.user.fs;

import jsinterop.annotations.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.bases.*;

public class SecretLink {

    public final PublicKeyHash owner;
    public final long label;

    public SecretLink(PublicKeyHash owner, long label) {
        if (label <= 0)
            throw new IllegalStateException("Link labels must be positive!");
        this.owner = owner;
        this.label = label;
    }

    public String toLink() {
        return "secret/" + Multibase.Base.Base58BTC.prefix + owner.toBase58() + "/" + labelString();
    }

    public String labelString() {
        return "" + label;
    }

    @JsMethod
    public static SecretLink fromLink(String link) {
        String[] parts = link.split("/");
        if (parts.length != 3)
            throw new IllegalStateException("Invalid secret link");
        PublicKeyHash owner = PublicKeyHash.fromString(parts[1]);
        long ref = Long.parseLong(parts[2]);
        return new SecretLink(owner, ref);
    }
}
