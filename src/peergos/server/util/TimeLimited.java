package peergos.server.util;

import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.storage.*;

import java.util.*;

public class TimeLimited {

    public static byte[] signNow(SecretSigningKey signer) {
        byte[] time = new CborObject.CborLong(System.currentTimeMillis()).serialize();
        return signer.signMessage(time);
    }

    public static boolean isAllowedTime(byte[] signedTime, ContentAddressedStorage ipfs, PublicKeyHash owner) {
        try {
            Optional<PublicSigningKey> ownerOpt = ipfs.getSigningKey(owner).get();
            if (! ownerOpt.isPresent())
                throw new IllegalStateException("Couldn't retrieve owner key during getFollowRequests() call!");
            byte[] raw = ownerOpt.get().unsignMessage(signedTime);
            CborObject cbor = CborObject.fromByteArray(raw);
            if (! (cbor instanceof CborObject.CborLong))
                throw new IllegalStateException("Invalid cbor for getFollowRequests authorisation!");
            long utcMillis = ((CborObject.CborLong) cbor).value;
            long now = System.currentTimeMillis();
            if (Math.abs(now - utcMillis) > 300_000)
                throw new IllegalStateException("Stale auth time in getFollowRequests, is your clock accurate?");
            // This is a valid request
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return true;
    }
}
