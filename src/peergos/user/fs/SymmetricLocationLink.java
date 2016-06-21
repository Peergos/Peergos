package peergos.user.fs;

import peergos.crypto.*;
import peergos.crypto.symmetric.SymmetricKey;
import peergos.util.*;

import java.io.*;
import java.util.*;

public class SymmetricLocationLink {
    public final byte[] link;
    public final byte[] loc;

    public SymmetricLocationLink(byte[] link, byte[] location) {
        this.link = link;
        this.loc = location;
    }

    public Location targetLocation(SymmetricKey from) {
        byte[] nonce = Arrays.copyOfRange(loc, 0, TweetNaCl.SECRETBOX_NONCE_BYTES);
        byte[] rest = Arrays.copyOfRange(loc, TweetNaCl.SECRETBOX_NONCE_BYTES, loc.length);
        return Location.decrypt(from, nonce, rest);
    }

    public SymmetricKey target(SymmetricKey from) {
        byte[] linkNonce = Arrays.copyOfRange(link, 0, TweetNaCl.SECRETBOX_NONCE_BYTES);
        byte[] rest = Arrays.copyOfRange(link, TweetNaCl.SECRETBOX_NONCE_BYTES, link.length);
        byte[] encoded = from.decrypt(rest, linkNonce);
        return SymmetricKey.deserialize(encoded);
    }

    public byte[] serialize() {
        DataSink buf = new DataSink();
        buf.writeArray(link);
        buf.writeArray(loc);
        return buf.toByteArray();
    }

    public ReadableFilePointer toReadableFilePointer(SymmetricKey baseKey) throws IOException {
       Location loc =  targetLocation(baseKey);
       SymmetricKey key = target(baseKey);
       return new ReadableFilePointer(loc.owner, loc.writer, loc.mapKey, key);
    }

    public static SymmetricLocationLink deserialize(byte[] raw) throws IOException {
        DataSource source = new DataSource(raw);
        return new SymmetricLocationLink(source.readArray(), source.readArray());
    }

    public static SymmetricLocationLink create(SymmetricKey fromKey, SymmetricKey toKey, Location location) {
        byte[] locNonce = fromKey.createNonce();
        byte[] loc = ArrayOps.concat(locNonce, location.encrypt(fromKey, locNonce));
        byte[] linkNonce = fromKey.createNonce();
        byte[] link = ArrayOps.concat(linkNonce, fromKey.encrypt(toKey.serialize(), linkNonce));
        return new SymmetricLocationLink(link, loc);
    }
}
