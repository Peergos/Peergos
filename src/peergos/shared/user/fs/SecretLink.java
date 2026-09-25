package peergos.shared.user.fs;

import jsinterop.annotations.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.random.*;
import peergos.shared.io.ipfs.bases.*;

public class SecretLink {

    /** How the server words a link it will not serve, whatever is asked again: the start of each
     *  message, so a server that words the rest differently still matches. */
    public static final String MISSING = "No secret link";
    public static final String EXPIRED = "Secret link expired";
    public static final String USED_UP = "Maximum link retrievals";

    /** Whether an error is the server refusing a link for good. A browser receives the message still
     *  url encoded, spaces as plus signs, so both forms are recognised. */
    public static boolean isRefused(Throwable t) {
        String message = t == null ? null : t.getMessage();
        if (message == null)
            return false;
        String plain = message.replace('+', ' ');
        return plain.contains(MISSING) || plain.contains(EXPIRED) || plain.contains(USED_UP);
    }

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

    @JsMethod
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

    public static SecretLink create(PublicKeyHash owner, SafeRandom r) {
        byte[] labelBytes = r.randomBytes(4);
        long label = (labelBytes[0] & 0xFF) | ((labelBytes[1] & 0xFF) << 8) | ((labelBytes[2] & 0xFF) << 16) | (((labelBytes[3] & 0xFF) << 24) & 0xFFFFFFFFL);
        String linkPassword = EncryptedCapability.createLinkPassword(r);
        return new SecretLink(owner, label, linkPassword);
    }
}
