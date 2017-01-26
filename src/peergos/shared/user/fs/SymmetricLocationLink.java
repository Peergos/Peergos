package peergos.shared.user.fs;

import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.symmetric.SymmetricKey;
import peergos.shared.util.*;

import java.io.*;
import java.util.*;

public class SymmetricLocationLink implements Cborable {
    public final SymmetricLink link;
    public final byte[] loc;

    public SymmetricLocationLink(SymmetricLink link, byte[] location) {
        this.link = link;
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

    @Override
    public CborObject toCbor() {
        return new CborObject.CborList(Arrays.asList(
                link.toCbor(),
                new CborObject.CborByteArray(loc)
        ));
    }

    public FilePointer toReadableFilePointer(SymmetricKey baseKey) {
       Location loc =  targetLocation(baseKey);
       SymmetricKey key = target(baseKey);
       return new FilePointer(loc.owner, loc.writer, loc.getMapKey(), key);
    }

    public static SymmetricLocationLink fromCbor(CborObject cbor) {
        if (! (cbor instanceof CborObject.CborList))
            throw new IllegalStateException("Incorrect cbor type for SymmetricLocationLink: " + cbor);

        List<CborObject> value = ((CborObject.CborList) cbor).value;
        return new SymmetricLocationLink(SymmetricLink.fromCbor(value.get(0)), ((CborObject.CborByteArray)value.get(1)).value);
    }

    public static SymmetricLocationLink create(SymmetricKey fromKey, SymmetricKey toKey, Location location) {
        byte[] locNonce = fromKey.createNonce();
        byte[] loc = ArrayOps.concat(locNonce, location.encrypt(fromKey, locNonce));
        return new SymmetricLocationLink(SymmetricLink.fromPair(fromKey, toKey), loc);
    }
}
