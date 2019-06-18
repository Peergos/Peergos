package peergos.shared.util;

import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.*;

public class TimeLimitedClient {

    public static byte[] signNow(SecretSigningKey signer) {
        byte[] time = new CborObject.CborLong(System.currentTimeMillis()).serialize();
        return signer.signMessage(time);
    }
}
