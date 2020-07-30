package peergos.server.util;

import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.storage.*;
import peergos.shared.util.*;

import java.util.*;

public class TimeLimited {

    /**
     *
     * @param signedTime
     * @param durationSeconds
     * @param ipfs
     * @param owner
     * @return The time in milliseconds UTC that was signed and valid
     */
    public static long isAllowedTime(byte[] signedTime, int durationSeconds, ContentAddressedStorage ipfs, PublicKeyHash owner) {
        try {
            Optional<PublicSigningKey> ownerOpt = ipfs.getSigningKey(owner).get();
            if (! ownerOpt.isPresent())
                throw new IllegalStateException("Couldn't retrieve owner key!");
            byte[] raw = ownerOpt.get().unsignMessage(signedTime);
            CborObject cbor = CborObject.fromByteArray(raw);
            if (! (cbor instanceof CborObject.CborLong))
                throw new IllegalStateException("Invalid cbor for time in authorisation!");
            long utcMillis = ((CborObject.CborLong) cbor).value;
            long now = System.currentTimeMillis();
            if (Math.abs(now - utcMillis) > durationSeconds * 1_000)
                throw new IllegalStateException("Stale auth time, is your clock accurate?");
            // This is a valid request
            return utcMillis;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     *
     * @param expectedPath
     * @param signedReq
     * @param durationSeconds
     * @param ipfs
     * @param owner
     * @return The time in milliseconds UTC that was signed and valid
     */
    public static long isAllowed(String expectedPath, byte[] signedReq, int durationSeconds, ContentAddressedStorage ipfs, PublicKeyHash owner) {
        try {
            Optional<PublicSigningKey> ownerOpt = ipfs.getSigningKey(owner).get();
            if (! ownerOpt.isPresent())
                throw new IllegalStateException("Couldn't retrieve owner key!");
            byte[] raw = ownerOpt.get().unsignMessage(signedReq);
            CborObject cbor = CborObject.fromByteArray(raw);

            TimeLimitedClient.SignedRequest req = TimeLimitedClient.SignedRequest.fromCbor(cbor);
            long utcMillis = req.createdEpochMillis;
            long now = System.currentTimeMillis();
            if (Math.abs(now - utcMillis) > durationSeconds * 1_000)
                throw new IllegalStateException("Stale auth time, is your clock accurate?");
            if (! expectedPath.equals(req.path))
                throw new IllegalStateException("Illegal path for signed request: " + req.path);
            // This is a valid request
            return utcMillis;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
