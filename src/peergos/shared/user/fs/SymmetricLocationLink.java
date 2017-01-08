package peergos.shared.user.fs;

import peergos.shared.crypto.*;
import peergos.shared.crypto.symmetric.SymmetricKey;
import peergos.shared.util.*;

import java.io.*;
import java.util.*;

public class SymmetricLocationLink {
    public final SymmetricLink link;
    public final byte[] loc;

    public SymmetricLocationLink(byte[] link, byte[] location) {
        this.link = new SymmetricLink(link);
        this.loc = location;
    }

    public Location targetLocation(SymmetricKey from) {
        byte[] nonce = Arrays.copyOfRange(loc, 0, TweetNaCl.SECRETBOX_NONCE_BYTES);
        byte[] rest = Arrays.copyOfRange(loc, TweetNaCl.SECRETBOX_NONCE_BYTES, loc.length);
        return Location.decrypt(from, nonce, rest);
    }

    public SymmetricKey target(SymmetricKey from) {
        return link.target(from);
    }

    public byte[] serialize() {
        DataSink buf = new DataSink();
        buf.writeArray(link.serialize());
        buf.writeArray(loc);
        return buf.toByteArray();
    }

    public FilePointer toReadableFilePointer(SymmetricKey baseKey) {
       Location loc =  targetLocation(baseKey);
       SymmetricKey key = target(baseKey);
       return new FilePointer(loc.owner, loc.writer, loc.getMapKey(), key);
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
