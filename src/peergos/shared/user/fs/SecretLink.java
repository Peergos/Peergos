package peergos.shared.user.fs;

import jsinterop.annotations.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.bases.*;

public class SecretLink {

    public final PublicKeyHash owner;
    public final long label;
    public final String linkPassword;

    public SecretLink(PublicKeyHash owner, long label, String linkPassword) {
        if (label <= 0)
            throw new IllegalStateException("Link labels must be positive!");
        this.owner = owner;
        this.label = label;
        this.linkPassword = linkPassword;
    }

    public String toLink() {
        return "secret/" + Multibase.Base.Base58BTC.prefix + owner.toBase58() + "/" + labelString() + "#" + linkPassword;
    }

    public String labelString() {
        return "" + label;
    }

    @JsMethod
    public static SecretLink fromLink(String link) {
        // e.g secret/z59vuwzfFDorjWRiEtcEu6BQWWsLYCAJpmkAcVkuV8P5b4ykYwm1NE6/8057131#moCvfdkPxWLb
        if (link.startsWith("/"))
            link = link.substring(1);
        int hashIndex = link.indexOf("#");
        String fragment = link.substring(hashIndex + 1);
        if (fragment.contains("?"))
            fragment = fragment.substring(0, fragment.indexOf("?"));
        String[] parts = link.substring(0, hashIndex).split("/");
        if (parts.length != 3)
            throw new IllegalStateException("Invalid secret link");
        PublicKeyHash owner = PublicKeyHash.fromString(parts[1]);
        long ref = Long.parseLong(parts[2]);
        return new SecretLink(owner, ref, fragment);
    }
}
